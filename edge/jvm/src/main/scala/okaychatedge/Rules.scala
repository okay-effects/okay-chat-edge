package okaychatedge

/** okay-chat's RULES on the JVM: Java's regexes, as okay-chat writes them (`(?iU)`) — nothing to translate */
object Rules:
  def compile(java: String): Re =
    require(java.startsWith("(?iU)"), s"a rule without (?iU): $java")
    Re.java(java)
