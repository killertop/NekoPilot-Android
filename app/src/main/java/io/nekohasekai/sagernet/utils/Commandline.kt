package io.nekohasekai.sagernet.utils

/**
 * Commandline objects help handling command lines specifying processes to
 * execute.
 *
 * The class can be used to define a command line as nested elements or as a
 * helper to define a command line by an application.
 *
 *
 * `
 * <someelement><br></br>
 * &nbsp;&nbsp;<acommandline executable="/executable/to/run"><br></br>
 * &nbsp;&nbsp;&nbsp;&nbsp;<argument value="argument 1" /><br></br>
 * &nbsp;&nbsp;&nbsp;&nbsp;<argument line="argument_1 argument_2 argument_3" /><br></br>
 * &nbsp;&nbsp;&nbsp;&nbsp;<argument value="argument 4" /><br></br>
 * &nbsp;&nbsp;</acommandline><br></br>
 * </someelement><br></br>
` *
 *
 * Based on: https://github.com/apache/ant/blob/588ce1f/src/main/org/apache/tools/ant/types/Commandline.java
 *
 * Adds support for escape character '\'.
 */
object Commandline {

    /**
     * Quote the parts of the given array in way that makes them
     * usable as command line arguments.
     * @param args the list of arguments to quote.
     * @return empty string for null or no command, else every argument split
     * by spaces and quoted by quoting rules.
     */
    fun toString(args: Iterable<String>?): String {
        // empty path return empty string
        args ?: return ""
        // path containing one or more elements
        val result = StringBuilder()
        for (arg in args) {
            if (result.isNotEmpty()) result.append(' ')
            arg.forEach { character ->
                when (character) {
                    ' ', '\\', '"', '\'' -> {
                        result.append('\\')  // intentionally no break
                        result.append(character)
                    }
                    else -> result.append(character)
                }
            }
        }
        return result.toString()
    }
}
