package com.lagradost.cloudstream3.ui.iptv

object IptvParser {

    fun parse(content: String): List<IptvChannel> {
        val result = mutableListOf<IptvChannel>()

        var name: String? = null
        var logo: String? = null
        var group: String? = null

        content
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .forEach { raw ->
                val line = raw.trim()

                if (line.isBlank()) return@forEach

                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    logo = attribute(line, "tvg-logo")
                    group = attribute(line, "group-title")

                    name = line
                        .substringAfterLast(",")
                        .trim()
                        .ifBlank { "Unknown Channel" }

                    return@forEach
                }

                if (
                    !line.startsWith("#") &&
                    name != null &&
                    line.isNotBlank()
                ) {
                    result += IptvChannel(
                        name = name!!,
                        streamUrl = line,
                        logoUrl = logo,
                        groupTitle = group
                    )

                    name = null
                    logo = null
                    group = null
                }
            }

        return result
    }

    private fun attribute(
        line: String,
        key: String
    ): String? {
        val regex = Regex(
            """$key\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }
}
