package Assembler

/**
 *汇编过程中抛出的异常
 */
class AssemblyException(private val originalText: String?,
                        private val originalLineNumber: Int?,
                        private val msg: String) : Exception() {
    override val message: String?
        get() = if (originalLineNumber != null && originalText != null) {
            "Error at line $originalLineNumber : \"$originalText\"\n\t$msg"
        } else {
            "Error: $msg"
        }

}