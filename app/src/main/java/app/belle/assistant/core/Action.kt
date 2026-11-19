package app.belle.assistant.core

/** One thing the assistant should do. All argument values are strings. */
data class Action(val tool: String, val args: Map<String, String> = emptyMap()) {
    fun arg(name: String): String = args[name]?.trim().orEmpty()
    fun intArg(name: String): Int? = arg(name).toIntOrNull()
}

sealed class ParamType {
    object Text : ParamType()
    object Int : ParamType()
    data class Choice(val values: List<String>) : ParamType()
}

data class Param(val name: String, val type: ParamType, val desc: String, val required: Boolean = false)

data class ToolSpec(val name: String, val desc: String, val params: List<Param> = emptyList())
