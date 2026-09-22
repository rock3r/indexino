package fixture.use

import fixture.library.Ledger as Book
import fixture.java.JavaLedger

fun use(book: Book, java: JavaLedger) {
    book.mark()
    book.mark(7)
    java.ping()
}

fun shadow(book: Book) {
    val book = JavaLedger()
    book.mark()
}

fun overload(book: Book) {
    book.choose(7)
    book.choose("seven")
}
