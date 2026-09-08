package com.zz.filemanager.core.operation

import java.util.Locale

object FileNameRules {
    fun validateLeafName(name: String): OperationFailure? {
        if (name.isBlank()) return invalid("Name cannot be empty.")
        if (name == "." || name == "..") return invalid("This name is reserved.")
        if (name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0) {
            return invalid("A file name cannot contain path separators or a NUL character.")
        }
        return null
    }

    fun splitName(name: String, isDirectory: Boolean = false): Pair<String, String> {
        if (isDirectory) return name to ""
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.lastIndex) {
            name.substring(0, dot) to name.substring(dot)
        } else {
            name to ""
        }
    }

    fun keepBothCandidate(originalName: String, copyIndex: Int, isDirectory: Boolean): String {
        require(copyIndex >= 1)
        val (base, extension) = splitName(originalName, isDirectory)
        return "$base ($copyIndex)$extension"
    }

    fun normalizedCollisionKey(name: String): String = name.lowercase(Locale.ROOT)

    private fun invalid(message: String) = OperationFailure(
        code = OperationFailureCode.INVALID_NAME,
        message = message,
    )
}
