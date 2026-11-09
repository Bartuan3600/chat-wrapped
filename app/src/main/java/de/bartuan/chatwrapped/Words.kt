package de.bartuan.chatwrapped


object Words {
    val stop: Set<String> = ("ich du und die der das ist nicht aber dann ja nein nee ne so auch es in zu mit den dem des ein eine einen einem einer eines " +
        "auf für fur was wie wo wer noch schon mal nur hab habe hast hat haben bin bist sind war waren wird werden wurde kann kannst können konnte " +
        "mir mich dich dir wir ihr sie er ihm ihn uns euch von vom im am an bei beim oder wenn weil dass das als da jetzt doch halt ok okay oke oh ah eh " +
        "man sich sein seine mein meine meinen dein deine um aus nach vor bis gut alles denn sehr gerade grad eig eigentlich mehr heute morgen hier dort " +
        "ins zum zur über ueber unter wieder immer gar kein keine keinen nichts etwas was's gibt geht gehen machen mache macht muss musst will willst " +
        "würde wäre hätte hatte soll sollte ganz echt einfach also ob dieser diese dieses diesen welche nochmal wohl eben ach naja hm hmm mhm jo jaa jaaa " +
        "the a an and or to of is it you i me my we be that this for on in at so but not no yes just do are was were have has with your it's i'm im " +
        "dont don't can can't will would what when how all if they he she them his her its our out up get got like there then than too also very " +
        "weggelassen medien ausgeschlossen media omitted nachricht gelöscht bild sticker audio video angehängt attached").split(' ').filter { it.isNotBlank() }.toSet()

    /** Love: words/emojis; matched as word prefixes so 'vermisse'/'liebe dich' count, but 'beliebig' doesn't. */
    val lovePrefixes = listOf("liebe", "lieb dich", "hab dich lieb", "hdl", "hdgdl", "ild", "schatz", "vermiss", "kuss", "küss", "knutsch",
        "love", "luv", "miss you", "babe", "baby", "sweetheart", "darling", "honey", "mausi", "süße", "süsse", "<3")
    val loveEmojis = listOf("❤", "😘", "🥰", "😍", "💕", "💖", "💗", "💘", "💞", "💓", "😻", "💋", "🫶")
    val insultWords = listOf("idiot", "idioten", "depp", "blöd", "blöde", "doof", "dumm", "dummkopf", "arsch", "arschloch", "penner", "lappen",
        "trottel", "vollidiot", "spast", "opfer", "kacke", "scheiße", "scheisse", "scheiß", "scheiss", "hurensohn", "wichser", "fotze", "bastard",
        "stupid", "dumbass", "moron", "asshole", "bitch", "fuck", "fucking", "shit", "jerk", "loser", "dick")
    private val wordRx = Regex("""[\p{L}][\p{L}\p{N}'’]*""")
    val laughRx = Regex("""(?i)(?<![\p{L}])((ha|he|hi){2,}h?|a?ha(ha)+|xd+|lol+|lmao|lmfao|rofl|haha\w*|hihi\w*)(?![\p{L}])|😂|🤣|😆|😹|😅""")
    val linkRx = Regex("""(?i)\bhttps?://\S+|\bwww\.\S+""")

    fun tokens(t: String): List<String> = wordRx.findAll(t.lowercase()).map { it.value.trimEnd('\'', '’') }.filter { it.length > 1 }.toList()

    fun loveCount(t: String): Int {
        val l = t.lowercase()
        var n = 0
        for (p in lovePrefixes) {
            var i = l.indexOf(p)
            while (i >= 0) {
                val before = if (i == 0) ' ' else l[i - 1]
                val end = i + p.length
                val after = if (end >= l.length) ' ' else l[end]
                if (!before.isLetter() && (p.length > 4 || !after.isLetter())) n++
                i = l.indexOf(p, i + p.length)
            }
        }
        for (e in loveEmojis) n += Regex(Regex.escape(e)).findAll(t).count()
        return n
    }

    fun insultCount(tokens: List<String>): Int = tokens.count { it in insultWords }

    fun isEmoji(cluster: String): Boolean {
        val cp = cluster.codePointAt(0)
        if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF) return cp !in 0x1F3FB..0x1F3FF
        if (cp in listOf(0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x3030, 0x303D)) return cluster.contains('\uFE0F')
        if (cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x25A0..0x25FF) return cluster.contains('\uFE0F') || cp in 0x23E9..0x23FA
        return false
    }

    /** Emoji clusters incl. ZWJ sequences, skin tones (stripped) and flags; independent of the JVM's BreakIterator. */
    fun emojis(t: String): List<String> {
        val res = ArrayList<String>()
        val cps = t.codePoints().toArray()
        var i = 0
        fun isRegional(c: Int) = c in 0x1F1E6..0x1F1FF
        fun isMod(c: Int) = c in 0x1F3FB..0x1F3FF
        while (i < cps.size) {
            val c = cps[i]
            val hasVs = i + 1 < cps.size && cps[i + 1] == 0xFE0F
            if (!isEmoji(String(Character.toChars(c)) + if (hasVs) "\uFE0F" else "")) { i++; continue }
            val sb = StringBuilder().appendCodePoint(c)
            i++
            if (isRegional(c) && i < cps.size && isRegional(cps[i])) { sb.appendCodePoint(cps[i]); i++ }
            while (i < cps.size) {
                val n = cps[i]
                when {
                    n == 0xFE0F || isMod(n) || n == 0x20E3 -> i++ // drop variation selector / skin tone
                    n == 0x200D && i + 1 < cps.size -> { sb.appendCodePoint(0x200D).appendCodePoint(cps[i + 1]); i += 2 }
                    else -> break
                }
            }
            res.add(sb.toString())
        }
        return res
    }
}
