package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.legacy.LegacyMapping
import org.cadixdev.lorenz.MappingSet
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.VirtualJvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.pathString
import kotlin.system.exitProcess

private val fileDelimiter = Paths.get("/").pathString

/**
 * Build an in-memory VFS under TempFileSystem from a map of Unix-style paths to file contents.
 * @param files  map of "dir1/dir2/file.ext" → fileText
 * @return       map of the same keys to their created VirtualFile
 */
fun createMockFileSystem(files: Map<String, String>): Pair<Map<String, VirtualFile>, VirtualDirectory> {

    // 1) grab the singleton TempFileSystem and its invisible root
    val root = VirtualDirectory("", null)

    // 2) for each entry, traverse/create directories, then write the file
    return files.mapValues { (path, content) ->
        // split on "/" (or "\" on Windows, if needed)
        val segments = path.split(fileDelimiter)
        // walk/build the directory path
        var dir = root
        for (segment in segments.dropLast(1)) {
            dir = (dir.findChild(segment) as? VirtualDirectory)
                ?: dir.addNewDirectory(segment)
        }
        // create the leaf file and set its contents

        val file = VirtualParentalFile(segments.last(), content)
        dir.addChild(file)
        file
    } to root
}

class Transformer(private val map: MappingSet, val patternMappings: List<PatternMapping> = emptyList()) {
    var classpath: Array<String>? = null
    var remappedClasspath: Array<String>? = null
    var jdkHome: File? = null
    var remappedJdkHome: File? = null
    var patternAnnotation: String? = null
    var manageImports = false
    var verboseCompilerMessages = false

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> =
        remap(sources, emptyMap(), emptyMap())

    @Throws(IOException::class)
    fun remap(
        sources: Map<String, String>,
        referenceSources: Map<String, String>,
        processedSources: Map<String, String>,
    ): Map<String, Pair<String, List<Pair<Int, String>>>> {
        val processedTmpDir = Files.createTempDirectory("remap-processed")
        val disposable = Disposer.newDisposable()
        try {
            val combinedSource = referenceSources.plus(sources)

            for ((unitName, source) in combinedSource) {
                val processedSource = processedSources[unitName] ?: source
                val processedPath = processedTmpDir.resolve(unitName)
                Files.createDirectories(processedPath.parent)
                Files.write(processedPath, processedSource.toByteArray(), StandardOpenOption.CREATE)
            }

            val config = CompilerConfiguration()
            config.put(CommonConfigurationKeys.MODULE_NAME, "main")
            jdkHome?.let { config.setupJdk(it) }

            val (virtualFiles, tmpDir) = createMockFileSystem(combinedSource)

            config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(tmpDir.toNioPath(),"")) // SAD
            config.addAll<ContentRoot>(
                CLIConfigurationKeys.CONTENT_ROOTS,
                classpath!!.map { JvmClasspathRoot(File(it)) })
            config.put<MessageCollector>(
                CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
            )

            // Our PsiMapper only works with the PSI tree elements, not with the faster (but kotlin-specific) classes
            config.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)

            // Mark Registry as loaded, otherwise RegistryKey will (provided a sufficiently complex project) log
            // messages about it being accessed before it is loaded (and it won't ever be loaded naturally).
            val loadedField = try {
                Registry::class.java.getDeclaredField("myLoaded")
            } catch (_: NoSuchFieldException) {
                Registry::class.java.getDeclaredField("isLoaded")
            }
            loadedField.isAccessible = true
            loadedField.set(Registry.getInstance(), true)

            val environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                config,
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )

            @Suppress("DEPRECATION")
            val rootArea = Extensions.getRootArea()
            synchronized(rootArea) {
                if (!rootArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                    rootArea.registerExtensionPoint(
                        CustomExceptionHandler.KEY.name,
                        CustomExceptionHandler::class.java.name,
                        ExtensionPoint.Kind.INTERFACE
                    )
                }
            }

            val project = environment.project as MockProject
            val psiManager = PsiManager.getInstance(project)

            val psiFiles = virtualFiles.mapValues { psiManager.findFile(it.value)!! }
            val ktFiles = psiFiles.values.filterIsInstance<KtFile>()

            val analysis = analyze(environment, ktFiles)

            val remappedEnv = remappedClasspath?.let {
                setupRemappedProject(disposable, it, processedTmpDir)
            }

            val patterns = patternAnnotation?.let { annotationFQN ->
                val patterns = PsiPatterns(annotationFQN)
                val annotationName = annotationFQN.substring(annotationFQN.lastIndexOf('.') + 1)
                for ((unitName, source) in sources) {
                    if (!source.contains(annotationName)) continue
                    try {
                        val patternFile = virtualFiles[unitName]!!
                        val patternPsiFile = psiManager.findFile(patternFile)!!
                        patterns.read(patternPsiFile, processedSources[unitName]!!)
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to read patterns from file \"$unitName\".", e)
                    }
                }
                patterns
            }

            val autoImports = if (manageImports && remappedEnv != null) {
                AutoImports(remappedEnv)
            } else {
                null
            }

            val results = HashMap<String, Pair<String, List<Pair<Int, String>>>>()
            for (name in sources.keys) {
                val file = virtualFiles[name]!!
                val psiFile = psiManager.findFile(file)!!

                var (text, errors) = try {
                    PsiMapper(
                        map,
                        remappedEnv?.project,
                        psiFile,
                        analysis.bindingContext,
                        patterns,
                        patternMappings
                    ).remapFile()
                } catch (e: Exception) {
                    throw RuntimeException("Failed to map file \"$name\".", e)
                }

                if (autoImports != null && "/* remap: no-manage-imports */" !in text) {
                    val processedText = processedSources[name] ?: text
                    text = autoImports.apply(psiFile, text, processedText)
                }

                results[name] = text to errors
            }
            return results
        } finally {
            Files.walk(processedTmpDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            Disposer.dispose(disposable)
        }
    }

    private fun CompilerConfiguration.setupJdk(jdkHome: File) {
        put(JVMConfigurationKeys.JDK_HOME, jdkHome)

        if (!CoreJrtFileSystem.isModularJdk(jdkHome)) {
            val roots = PathUtil.getJdkClassesRoots(jdkHome).map { JvmClasspathRoot(it, true) }
            addAll(CLIConfigurationKeys.CONTENT_ROOTS, 0, roots)
        }
    }

    private fun setupRemappedProject(
        disposable: Disposable,
        classpath: Array<String>,
        sourceRoot: Path,
    ): KotlinCoreEnvironment {
        val config = CompilerConfiguration()
        (remappedJdkHome ?: jdkHome)?.let { config.setupJdk(it) }
        config.put(CommonConfigurationKeys.MODULE_NAME, "main")
        config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(File(it)) })
        if (manageImports) {
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(sourceRoot.toFile(), ""))
        }
        config.put(
            CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
        )

        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )

        analyze(environment, emptyList())
        return environment
    }

    companion object {

        @Throws(IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val mappings: MappingSet = if (args[0].isEmpty()) {
                MappingSet.create()
            } else {
                LegacyMapping.readMappingSet(File(args[0]).toPath(), args[1] == "true")
            }
            val transformer = Transformer(mappings)

            val reader = BufferedReader(InputStreamReader(System.`in`))

            transformer.classpath = (1..Integer.parseInt(args[2])).map { reader.readLine() }.toTypedArray()

            val sources = mutableMapOf<String, String>()
            while (true) {
                val name = reader.readLine()
                if (name == null || name.isEmpty()) {
                    break
                }

                val lines = arrayOfNulls<String>(Integer.parseInt(reader.readLine()))
                for (i in lines.indices) {
                    lines[i] = reader.readLine()
                }
                val source = lines.joinToString("\n")

                sources[name] = source
            }

            val results = transformer.remap(sources)

            for (name in sources.keys) {
                println(name)
                val lines = results.getValue(name).first.split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
                println(lines.size)
                for (line in lines) {
                    println(line)
                }
            }

            if (results.any { it.value.second.isNotEmpty() }) {
                exitProcess(1)
            }
        }
    }

}
