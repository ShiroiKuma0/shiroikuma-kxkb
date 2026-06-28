package com.urik.keyboard.dictionary

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Offline tool (NOT a normal test): dump every bundled .urik's words to /tmp/urikdump/<lang>.words so the
 * per-language cross-language pollution lists (`<lang>.removed`) can be regenerated with hunspell. See
 * `tools/clean_dictionaries.sh`. Guarded so it is a no-op in normal test runs; run it explicitly with
 * `./gradlew :app:testDebugUnitTest --tests *DictDumpTool -Durik.dump=1`.
 */
class DictDumpTool {
    @Test
    fun dumpAll() {
        assumeTrue("set -Durik.dump=1 to run the dictionary dump tool", System.getProperty("urik.dump") != null)
        val dictDir = File("src/main/assets/dictionaries")
        val outDir = File("/tmp/urikdump").apply { mkdirs() }
        dictDir.listFiles { f -> f.name.endsWith(".urik") }?.sortedBy { it.name }?.forEach { f ->
            val lang = f.name.removeSuffix(".urik")
            val words = f.inputStream().use { UrikDictionary(it).allWords() }
            File(outDir, "$lang.words").bufferedWriter().use { w ->
                words.forEach { (word, freq) -> w.write(word); w.write("\t"); w.write(freq.toString()); w.write("\n") }
            }
            println("DUMP $lang words=${words.size}")
        }
    }
}
