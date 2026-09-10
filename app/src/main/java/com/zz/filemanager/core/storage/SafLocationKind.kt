package com.zz.filemanager.core.storage

/** Explicit SAF purpose keeps removable/cloud roots distinguishable without inventing raw paths. */
enum class SafLocationKind(val storagePrefix: String) {
    GENERIC("saf"),
    SD_CARD("sd"),
    USB("usb"),
    CLOUD("cloud-saf"),
}

enum class RemovableStorageStatus {
    AVAILABLE,
    REMOVED,
    PERMISSION_LOST,
    CHANGED,
    READ_ONLY,
    UNSUPPORTED,
    UNKNOWN,
}
