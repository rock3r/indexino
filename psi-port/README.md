# psi-port

Un port in miniatura dell'architettura **PSI di IntelliJ** in Rust, con una
grammatica Java di sottoinsieme e un estrattore di dichiarazioni: la stessa
forma del layer `psi-syntax` che accio usa per Kotlin, Java e C#.

Non è — e non pretende di essere — un port dei parser di produzione. È la
dimostrazione verificabile che l'architettura si porta in Rust così:

| IntelliJ PSI | questo crate |
|---|---|
| `IElementType` / `TokenSet` | `kinds::SyntaxKind` (token + nodi, `#[repr(u16)]`) |
| `Lexer` (`start`/`advance`/`token_type`/`token_start`/`token_end`/`state`) | `lexer::JavaLexer`, stessa firma |
| `PsiBuilder` (`mark`/`done`/`precede`/`error`) | `parser::Parser`, marker su buffer di eventi |
| albero green immutabile + albero red | `rowan::GreenNode` + `rowan::SyntaxNode<JavaLanguage>` |
| `PsiParser.java` generato da `Java.bnf` | `grammar.rs` (sottoinsieme scritto a mano) |
| layer `extract` di accio | `extract.rs` + il binario `psidump` |

## Verifica

Su un repository di prova multilingue, `psidump` produce **gli stessi record**
di `accio dump` per i file Java — `path:line-end kind name parent`, 17/17
corrispondenze (accio aggiunge i fatti di firma `pname:`/`param:`/`type:`/
`returns:` che questo port non registra):

```console
$ cargo run -q --bin psidump demo/*.java
demo/OrderService.java:5-17 class OrderService
demo/OrderService.java:6-6 field calculator OrderService
demo/OrderService.java:9-12 method total OrderService
demo/OrderService.java:14-16 method discount OrderService
...
```

`cargo run --example dbg -- 'class A { ... }'` stampa l'albero completo
(token e nodi, trivia inclusi: l'albero green è lossless, ogni byte è un
token).

## Cosa serve per la parità con i port di accio

I tre parser portati da accio sono grandi, e i sorgenti upstream sono
pubblici (Apache-2.0):

- **Java**: `PsiParser.java` di `java-syntax`, generato da `Java.bnf` con
  Grammar Kit — decine di migliaia di righe generate più la grammatica;
- **Kotlin**: `KotlinParsing.java` (~99 KB) nel compilatore Kotlin;
- **C#**: `LanguageParser.cs` di Roslyn (~670 KB).

Un port fedele è un lavoro di mesi per lingua: recupero d'errore completo
(ogni costrutto con `parseXXXWithErrorMessage`), tutti i letterali e le
espressioni, enumerazioni/record/classi locali/anonime, ambiguità
dichiarazione/espressione, facts (import, call site, gate costanti). Questo
crate copre invece l'architettura end-to-end — lexer, marker, green/red
tree, estrazione — con una grammatica sufficiente per classi, membri e
corpi, e recupero d'errore a elementi `ErrorElement` che non fermano il
parse, come fa IntelliJ.

## Licenze

Il codice originale qui è MIT. Un port delle grammatiche JetBrains/Roslyn
dovrebbe rispettarne le licenze Apache-2.0 (i port di accio lo fanno, con
`LICENSE`/`NOTICE` dedicati nel pacchetto).
