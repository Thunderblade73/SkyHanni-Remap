package com.replaymod.gradle.remap

import org.jetbrains.kotlin.com.intellij.ide.highlighter.ArchiveFileType
import org.jetbrains.kotlin.com.intellij.openapi.util.io.BufferExposingByteArrayInputStream
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFileBase
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths
import kotlin.io.path.pathString

class VirtualDirectory(private val name: String, private val parent: VirtualFile?) :
    LightVirtualFileBase(name, ArchiveFileType.INSTANCE, 0) {

    private val children = mutableListOf<VirtualFile>()

    private fun addChild(child: VirtualDirectory): VirtualDirectory {
        children.add(child)
        return child
    }

    fun addChild(child: VirtualParentalFile): VirtualParentalFile {
        children.add(child)
        child.setParent(this)
        return child
    }

    fun addNewDirectory(name: String): VirtualDirectory =
        addChild(VirtualDirectory(name, this))

    override fun getName(): String = name

    override fun getPath(): String {
        if (parent == null) {
            return FileUtil.toSystemIndependentName("") + "!/"
        }
        val parentPath = parent.path
        val answer = StringBuilder(parentPath.length + 1 + name.length)
        answer.append(parentPath)
        if (answer[answer.length - 1] != '/') {
            answer.append('/')
        }
        answer.append(name)
        return answer.toString()
    }

    override fun isDirectory(): Boolean = true

    override fun getParent(): VirtualFile? = parent

    override fun getChildren(): Array<out VirtualFile?>? = children.toTypedArray()

    override fun isWritable(): Boolean = false

    override fun getOutputStream(p0: Any?, p1: Long, p2: Long): OutputStream {
        throw UnsupportedOperationException("VirtualDirectory is read-only")
    }

    override fun contentsToByteArray(): ByteArray = EMPTY_BYTE_ARRAY

    override fun getInputStream(): InputStream {
        return BufferExposingByteArrayInputStream(contentsToByteArray())
    }

    fun findChildRecursive(name: String): VirtualFile? {
        val segments = name.split(fileDelimiter)
        var dir = this
        for (segment in segments.dropLast(1)) {
            dir = dir.findChild(segment) as VirtualDirectory
        }
        return dir.findChild(segments.last())
    }

}

private val fileDelimiter = Paths.get("/").pathString

private val EMPTY_BYTE_ARRAY = ByteArray(0)