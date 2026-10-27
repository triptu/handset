package ai.tushar.handset.transport.relay

/** Client names are self-reported; keep them to one short line without invisible formatting. */
internal fun relayClientName(name: String): String = name.filter {
    !it.isISOControl() && Character.getType(it) !in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
}.trim().take(40).ifEmpty { "Unnamed client" }
