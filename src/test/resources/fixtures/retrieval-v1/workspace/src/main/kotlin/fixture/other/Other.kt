package fixture.other

class Ledger {
    fun mark() {}
}

fun unrelated(other: Ledger) {
    other.mark()
    val decoy = "book.mark()"
    // book.mark()
}
