package sca.merge

import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

data class FieldRule(val app: List<String>, val os: List<String>)

data class MergeRules(val fields: Map<String, FieldRule>) {
    fun order(field: String, os: Boolean): List<String> =
        fields[field]?.let { if (os) it.os else it.app } ?: error("merge-rules.yml has no rule for '$field'")

    companion object {
        fun load(): MergeRules = MergeRules::class.java.getResourceAsStream("/merge-rules.yml")!!.use {
            YAMLMapper.builder().addModule(kotlinModule()).build().readValue(it)
        }
    }
}
