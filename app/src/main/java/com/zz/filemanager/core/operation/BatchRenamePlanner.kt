package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.FileEntry

data class BatchRenameRule(
    val find: String = "",
    val replaceWith: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val sequenceEnabled: Boolean = false,
    val sequenceStart: Long = 1L,
    val sequencePadding: Int = 3,
    val sequenceSeparator: String = "_",
)

data class BatchRenamePreview(
    val itemId: String,
    val originalName: String,
    val proposedName: String,
    val valid: Boolean,
    val problem: String? = null,
)

object BatchRenamePlanner {
    fun preview(entries: List<FileEntry>, rule: BatchRenameRule): List<BatchRenamePreview> {
        val generated = entries.mapIndexed { index, entry ->
            val (base, extension) = FileNameRules.splitName(entry.name, entry.isDirectory)
            val replaced = if (rule.find.isEmpty()) base else base.replace(rule.find, rule.replaceWith)
            val sequence = if (rule.sequenceEnabled) {
                val value = rule.sequenceStart + index.toLong()
                rule.sequenceSeparator + value.toString().padStart(rule.sequencePadding.coerceIn(1, 12), '0')
            } else {
                ""
            }
            val proposed = rule.prefix + replaced + rule.suffix + sequence + extension
            val validation = FileNameRules.validateLeafName(proposed)
            BatchRenamePreview(
                itemId = entry.id,
                originalName = entry.name,
                proposedName = proposed,
                valid = validation == null,
                problem = validation?.message,
            )
        }.toMutableList()

        val duplicateNames = generated
            .groupBy { FileNameRules.normalizedCollisionKey(it.proposedName) }
            .filterValues { it.size > 1 }
            .keys

        if (duplicateNames.isNotEmpty()) {
            generated.indices.forEach { index ->
                val row = generated[index]
                if (FileNameRules.normalizedCollisionKey(row.proposedName) in duplicateNames) {
                    generated[index] = row.copy(valid = false, problem = "The batch produces duplicate names.")
                }
            }
        }
        return generated
    }
}
