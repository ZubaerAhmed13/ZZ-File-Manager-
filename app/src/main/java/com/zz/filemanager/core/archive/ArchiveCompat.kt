package com.zz.filemanager.core.archive

/** Nullable CharArray helper kept local to the archive package; Kotlin has no stdlib isNullOrEmpty for primitive arrays. */
internal fun CharArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()
