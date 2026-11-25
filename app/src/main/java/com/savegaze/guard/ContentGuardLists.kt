package com.savegaze.guard

/**
 * Built-in blocklists and commitment challenge sentences ported verbatim from ContentGuard.
 */
object ContentGuardLists {

    val DEFAULT_KEYWORDS: List<String> = listOf(
        "porn", "pornography", "pornhub", "xvideos", "xhamster", "xnxx", "redtube",
        "youporn", "tube8", "spankbang", "brazzers", "naughtyamerica", "realitykings",
        "bangbros", "mofos", "digitalplayground", "wicked", "babes", "twistys",
        "penthouse", "playboy", "hustler", "vivid", "adultfilmdatabase",
        "porntube", "xtube", "porntrex", "eporner", "tnaflix", "drtuber",
        "sunporno", "txxx", "hdzog", "hclips", "pornone", "3movs",
        "xxx", "xxxvideos", "xxxmovies", "xxxpics", "xxxporn",
        "anal", "analsex", "blowjob", "handjob", "deepthroat",
        "cumshot", "creampie", "facial", "gangbang", "threesome", "foursome",
        "orgy", "bukake", "bukkake", "fetish", "bondage", "bdsm",
        "dominatrix", "domination", "submission", "sadomasochism",
        "masturbation", "masturbate", "dildo", "vibrator", "fleshlight",
        "camgirl", "camshow", "livecam", "webcamgirl", "chaturbate",
        "livejasmin", "stripchat", "bongacams", "camsoda", "myfreecams",
        "onlyfans", "fansly", "manyvids", "clips4sale",
        "escort", "escorts", "escortservice", "callgirl",
        "brothel", "stripclub", "stripbar", "lapdance",
        "hentai", "hentaihaven", "hanime", "nhentai", "ehentai",
        "rule34", "r34", "gelbooru", "danbooru", "sankaku",
        "doujin", "doujinshi", "yaoi", "yuri", "ecchi", "futanari",
        "erotic", "erotica", "eroticism",
        "milf", "gilf", "dilf", "cougar",
        "twink", "bear", "twinks",
        "cuckold", "hotwife", "swinger", "swingers",
        "nudist", "nudism", "naturist",
        "voyeur", "voyeurism", "peeping",
        "exhibitionist", "exhibitionism", "flashing",
        "sexcam", "sexchat", "sexting", "cybersex",
        "adultcontent", "nsfwcontent", "explicitmaterial",
        "pornostar", "pornstar", "pornactress", "porndirector",
        "adulttoy", "sextoy", "adultshop", "sexshop",
        "adultvideo", "adultmovie", "adultfilm", "bluemovie",
        "striptease", "poledance", "lapdancing",
        "sugardaddy", "sugarbaby", "sugardate",
        "hookup", "bootyp", "bootycall",
        "slutty", "slutwear", "whorish",
        "nudefoto", "nudepics", "nudephotos", "nakedpics",
        "topless", "bottomless",
        "twerk", "twerking",
        "playmate", "centerfold",
        "porno", "softporn", "softcore", "hardcore",
        "interracial", "bbc", "bbw", "pawg",
        "gonewild", "nsfw", "nsfwgif",
        "sexworker", "prostitute", "prostitution",
        "bordello", "redlightdistrict",
        "camwhore", "gooner", "gooning",
        "spankwire", "motherless", "efukt",
        "heavy-r", "bestgore", "liveleak",
        "literotica", "asstr", "sexstory", "eroticstory",
        "ahegao", "tentacle", "lolicon", "shotacon",
        "upskirt", "downblouse", "creepshot",
        "jailbait", "underage",
        "zoophilia", "bestiality",
        "incest", "stepmom", "stepsister", "stepbrother", "stepdad",
        "taboo", "forbidden",
        "femdom", "maledom", "findom",
        "pegging", "fisting", "gaping",
        "squirting", "queefing",
        "footfetish", "footjob", "toesucking",
        "armpitfetish", "armpit",
        "watersports", "goldenshower",
        "scat", "coprophilia",
        "ballbusting", "cbt",
        "chastity", "chastitydevice", "cockring",
        "doublepenal", "doublepenetration",
        "gloryhole", "gloryholes",
        "sexdoll", "realdoll",
        "suggestivecontent", "lewd", "lewdness",
        "risque", "raunchy", "smut", "smutty",
        "adultentertainment", "adultweb", "adultsite",
        "cyberporn", "netporn", "webporn",
        "cammodel", "webcammodel", "adultmodel",
        "nudemodel", "glamourmodel", "lingeriepics",
        "pornblog", "nsfwblog", "adultblog",
        "darkweb", "deepweb",
        "seedysite", "explicitsite"
    )

    val DEFAULT_ADULT_DOMAINS: Set<String> = setOf(
        "pornhub.com", "xvideos.com", "xhamster.com", "xnxx.com", "redtube.com",
        "youporn.com", "tube8.com", "spankbang.com", "eporner.com", "tnaflix.com",
        "drtuber.com", "sunporno.com", "txxx.com", "hdzog.com", "hclips.com",
        "brazzers.com", "naughtyamerica.com", "realitykings.com", "bangbros.com",
        "mofos.com", "digitalplayground.com", "twistys.com", "babes.com",
        "penthouse.com", "playboy.com", "hustler.com", "vivid.com",
        "chaturbate.com", "livejasmin.com", "stripchat.com", "bongacams.com",
        "camsoda.com", "myfreecams.com", "cam4.com",
        "onlyfans.com", "fansly.com", "manyvids.com", "clips4sale.com",
        "nhentai.net", "ehentai.org", "hanime.tv", "rule34.xxx",
        "gelbooru.com", "danbooru.donmai.us", "sankakucomplex.com",
        "literotica.com", "motherless.com", "efukt.com",
        "heavy-r.com", "bestgore.fun", "adultfilmdatabase.com",
        "porntrex.com", "3movs.com", "pornone.com", "ponone.com",
        "xtube.com", "porntube.com"
    )

    val OPTIONAL_SOCIAL_DOMAINS: Set<String> = setOf(
        "instagram.com", "facebook.com", "reddit.com", "tiktok.com",
        "twitter.com", "x.com", "snapchat.com", "telegram.org", "web.telegram.org"
    )

    val GAMBLING_DOMAINS: Set<String> = setOf(
        "pokerstars.com", "bet365.com", "draftkings.com", "fanduel.com"
    )

    val COMMITMENT_SENTENCES: List<String> = listOf(
        "I acknowledge that bypassing this protection harms my personal growth, focus, and integrity; I consciously commit to staying disciplined 100%.",
        "Discipline is choosing between what you want now and what you want most; I will not compromise my standards for temporary impulses #2026.",
        "Surrendering to short-term temptation destroys long-term character; I affirm my resolve to master my mind and reject digital poison today!",
        "True freedom requires self-restraint and unwavering perseverance; I solemnly declare that I will resist destructive digital vices forever.",
        "Every time I conquer a weak impulse, my willpower becomes stronger; I refuse to let dopamine addiction dictate my life choices & future.",
        "Instant gratification is the enemy of greatness; I choose clarity, self-control, and moral strength over fleeting digital stimulation.",
        "I take full responsibility for my habits and actions; I will conquer procrastination and lust by building unbreakable inner willpower!",
        "A disciplined mind leads to an honorable life; I refuse to surrender my dignity, peace of mind, and valuable time to cheap dopamine."
    )

    val BROWSER_PACKAGES: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.opera.browser",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser",
        "org.torproject.torbrowser"
    )

    val URL_BAR_ID_HINTS: List<String> = listOf(
        "url_bar", "location_bar", "url_field", "mozac_browser_toolbar_url_view",
        "search_box_text", "omnibox", "address_bar", "addressbar", "url_edit"
    )

    val SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.samsung.android.lool",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )
}
