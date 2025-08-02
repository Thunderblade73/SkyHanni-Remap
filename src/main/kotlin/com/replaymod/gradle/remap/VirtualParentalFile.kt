package com.replaymod.gradle.remap

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile

class VirtualParentalFile(name: String, content: CharSequence) : LightVirtualFile(name, content) {

    private var parent : VirtualFile? = null

    override fun getParent(): VirtualFile? = parent

    fun setParent(parent: VirtualFile) {
        this.parent = parent
    }
}