package dev.sebastiano.indexino.model

public class CallQuery
private constructor(
    public val calleeName: String?,
    public val enclosingSymbolId: SymbolId?,
    public val callSiteId: CallSiteId?,
    public val file: SourceFile?,
) {
    public companion object {
        @JvmStatic
        public fun to(calleeName: String): CallQuery {
            require(calleeName.isNotBlank()) { "Call callee name must not be blank" }
            return CallQuery(calleeName, null, null, null)
        }

        @JvmStatic
        public fun enclosedBy(symbolId: SymbolId): CallQuery = CallQuery(null, symbolId, null, null)

        @JvmStatic
        public fun byId(callSiteId: CallSiteId): CallQuery = CallQuery(null, null, callSiteId, null)

        @JvmStatic
        public fun inFile(file: SourceFile): CallQuery = CallQuery(null, null, null, file)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CallQuery &&
                calleeName == other.calleeName &&
                enclosingSymbolId == other.enclosingSymbolId &&
                callSiteId == other.callSiteId &&
                file == other.file

    override fun hashCode(): Int {
        var result = calleeName?.hashCode() ?: 0
        result = 31 * result + (enclosingSymbolId?.hashCode() ?: 0)
        result = 31 * result + (callSiteId?.hashCode() ?: 0)
        result = 31 * result + (file?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CallQuery(calleeName=$calleeName, enclosingSymbolId=$enclosingSymbolId, " +
            "callSiteId=$callSiteId, file=$file)"
}
