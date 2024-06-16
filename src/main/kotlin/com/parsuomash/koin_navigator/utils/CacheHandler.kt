package com.parsuomash.koin_navigator.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.ConcurrentHashMap

internal val fileCache by lazy { ConcurrentHashMap<String, List<KtFile>>() }

class CacheHandler(private val project: Project) {
    init {
        setupFileListener()
    }

    private fun setupFileListener() {
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    when (event) {
                        is VFileCreateEvent, is VFileDeleteEvent -> {
                            updateCacheAsync()
                            break
                        }
                    }
                }
            }
        })
    }

    private fun updateCacheAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            ReadAction.run<RuntimeException> {
                updateCache()
            }
        }
    }

    fun updateCache(): List<KtFile> {
        val cacheKey = project.name
        val ktFiles = mutableListOf<KtFile>()
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        FilenameIndex.getAllFilesByExt(project, "kt", scope).forEach { virtualFile ->
            val psiFile = psiManager.findFile(virtualFile)
            if (psiFile is KtFile) {
                ktFiles.add(psiFile)
            }
        }

        fileCache[cacheKey] = ktFiles
        return ktFiles
    }
}
