package com.urik.keyboard.service.voice

/**
 * The commas Czech grammar requires before a subordinate clause, which Whisper-small often leaves out
 * ("Uvidíme jak…", "Myslím že…"): a comma goes in front of a subordinating conjunction or a relative pronoun
 * — or, when a preposition governs the pronoun ("dům ve kterém"), in front of the preposition.
 *
 * Deliberately conservative — a missing comma costs less than a wrong one:
 * - clause openers only (že, aby, protože, který…, jenž…); "co" and "než" are left alone ("co nejdřív",
 *   "větší než"), and "jak" gets its comma only where no comparison word comes before it ("tak jak",
 *   "stejně jak", "takový jak" stay as they are);
 * - never at a sentence start, never after a mark, never where a coordinating word already joins the clause
 *   ("a že", "i když", "ani aby", "ale protože").
 *
 * Pure text in, text out — unit-tested.
 */
object CzechCommas {
    private val CONJUNCTIONS = setOf(
        "že", "aby", "abych", "abys", "abychom", "abyste", "když", "protože", "poněvadž", "jestli", "jestliže",
        "pokud", "ačkoli", "ačkoliv", "přestože", "zatímco", "kdyby", "kdybych", "kdybys", "kdybychom",
        "kdybyste", "proč", "kde", "kam", "odkud", "kdy", "nakolik", "jak"
    )

    /** Before "jak" these make it a comparison, not a clause. */
    private val COMPARISON = setOf("tak", "stejně", "takový", "taková", "takové", "takoví", "přesně", "zrovna", "asi")

    private val RELATIVES = setOf(
        "který", "která", "které", "kteří", "kterého", "kterému", "kterém", "kterým", "kterou", "kterých", "kterými",
        "jenž", "jež", "jehož", "jemuž", "němž", "nichž", "jímž", "níž", "jíž", "nimž", "jejž", "něhož", "jichž", "jimž",
        "čí", "čího", "čemu", "čím"
    )

    private val PREPOSITIONS = setOf(
        "v", "ve", "na", "o", "s", "se", "z", "ze", "k", "ke", "u", "do", "od", "ode", "po", "pro", "při", "za",
        "před", "přede", "pod", "pode", "nad", "nade", "mezi", "bez", "beze", "přes", "proti", "kvůli", "díky", "podle"
    )

    /** Words that already join the clause: no comma between them and the conjunction. */
    private val JOINERS = setOf(
        "a", "i", "ani", "nebo", "či", "ale", "avšak", "jen", "jenom", "hlavně", "zejména", "právě", "teprve", "až",
        "zvlášť", "zvláště", "ledaže", "jako", "než", "anebo", "že", "aby", "protože", "když"
    )

    private const val CLAUSE_END = ".,;:!?…–—(\"„“«»"

    fun apply(text: String): String {
        val ranges = VoiceReviewCoordinator.wordRanges(text)
        if (ranges.size < 2) return text
        val words = ranges.map { (s, e) -> text.substring(s, e) }
        val cores = words.map { VoiceWordJudge.coreOf(it).lowercase() }
        val commaAfter = BooleanArray(words.size)
        for (i in 1 until words.size) {
            val core = cores[i]
            val opener = when {
                core in CONJUNCTIONS -> i
                core in RELATIVES -> if (cores[i - 1] in PREPOSITIONS && i >= 2) i - 1 else i
                else -> continue
            }
            // The comma would go after word opener-1.
            val before = opener - 1
            if (before < 0) continue
            // The opener must start cleanly (no leading mark), the word before must end cleanly.
            if (!words[opener].first().isLetter()) continue
            val prevWord = words[before]
            if (prevWord.last() in CLAUSE_END || !prevWord.last().isLetterOrDigit()) continue
            if (cores[before] in JOINERS || cores[before] in PREPOSITIONS) continue
            if (core == "jak" && cores[before] in COMPARISON) continue
            commaAfter[before] = true
        }
        if (commaAfter.none { it }) return text
        val sb = StringBuilder(text)
        for (i in words.indices.reversed()) {
            if (commaAfter[i]) sb.insert(ranges[i].second, ',')
        }
        return sb.toString()
    }
}
