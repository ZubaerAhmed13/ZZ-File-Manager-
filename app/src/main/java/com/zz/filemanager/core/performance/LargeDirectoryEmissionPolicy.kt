package com.zz.filemanager.core.performance

/** Bounded partial snapshots: first page, then exponentially spaced milestones. */
class LargeDirectoryEmissionPolicy(private val firstPageSize: Int = 256) {
    private var nextEmission = firstPageSize.coerceAtLeast(1)

    fun shouldEmit(size: Int): Boolean = size >= nextEmission

    fun onEmitted(size: Int) {
        nextEmission = maxOf(size + firstPageSize, nextEmission * 2)
    }
}
