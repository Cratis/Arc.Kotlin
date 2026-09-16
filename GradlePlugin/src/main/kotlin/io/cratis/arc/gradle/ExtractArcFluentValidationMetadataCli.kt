// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File

/** Manual-build bridge to the same strict compile/runtime inventory used by the Arc plugin task. */
public object ExtractArcFluentValidationMetadataCli {
    /** Arguments: compile classpath, runtime classpath, output index file. */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 3) { "Supply compile classpath, runtime classpath and output index file." }
        fun files(value: String): List<File> = value.split(File.pathSeparatorChar).filter(String::isNotBlank).map(::File)
        val bytes = ArcFluentValidationMetadataDiscovery.extract(files(arguments[0]), files(arguments[1]))
        val output = File(arguments[2])
        if (output.isFile && output.readBytes().contentEquals(bytes)) return
        output.parentFile.mkdirs()
        output.writeBytes(bytes)
    }
}
