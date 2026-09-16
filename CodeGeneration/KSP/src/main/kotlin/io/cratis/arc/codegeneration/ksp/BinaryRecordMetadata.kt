// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import java.io.File
import java.net.URI
import java.util.jar.JarFile
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.MethodVisitor

/** Reads private record field annotations that KSP's public binary symbols omit. Never loads a model class. */
internal class BinaryRecordMetadata(option: String?) {
    private val classpath = option?.split('|')?.filter(String::isNotBlank)?.map { entry ->
        val uri = URI(entry)
        require(uri.isAbsolute && uri.scheme == "file" && uri.query == null && uri.fragment == null) {
            "binary model classpath entries must be absolute file URIs"
        }
        File(uri).also { require(it.isDirectory || it.isFile && it.extension.equals("jar", true)) { "missing binary model classpath entry '$uri'" } }
    }.orEmpty()
    private val records = mutableMapOf<String, Map<String, List<SourceValidationAnnotation>>?>()

    fun fields(name: String): Map<String, List<SourceValidationAnnotation>>? = records.getOrPut(name) {
        val resource = name.replace('.', '/') + ".class"
        val candidates = classpath.mapNotNull { entry ->
            if (entry.isDirectory) entry.resolve(resource).takeIf(File::isFile)?.readBytes()
            else JarFile(entry, false, java.util.zip.ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { jar -> jar.getJarEntry(resource)?.let { jar.getInputStream(it).use { stream -> stream.readBytes() } } }
        }
        if (candidates.isEmpty()) return@getOrPut null
        val bytes = candidates.first()
        require(candidates.all(bytes::contentEquals)) { "conflicting binary model definitions for '$name'" }
        val result = linkedMapOf<String, MutableList<SourceValidationAnnotation>>()
        val reader = ClassReader(bytes)
        require(reader.className == resource.removeSuffix(".class") && reader.superName == "java/lang/Record") {
            "binary model '$name' is not the expected record"
        }
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                if (access and Opcodes.ACC_STATIC != 0) return null
                val annotations = result.getOrPut(name) { mutableListOf() }
                return object : FieldVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                        val qualifiedName = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
                        return annotation { arguments -> annotations += SourceValidationAnnotation(qualifiedName, arguments) }
                    }
                }
            }
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                val annotations = result[name] ?: return null
                if (access and Opcodes.ACC_STATIC != 0 || !descriptor.startsWith("()")) return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                        val qualifiedName = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
                        return annotation { arguments -> annotations += SourceValidationAnnotation(qualifiedName, arguments) }
                    }
                }
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        result.mapValues { it.value.toList() }
    }

    private fun annotation(complete: (Map<String, Any?>) -> Unit): AnnotationVisitor {
        val arguments = linkedMapOf<String, Any?>()
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String, value: Any) { arguments[name] = value }
            override fun visitEnum(name: String, descriptor: String, value: String) { arguments[name] = value }
            override fun visitArray(name: String): AnnotationVisitor {
                val values = mutableListOf<Any?>()
                arguments[name] = values
                return object : AnnotationVisitor(Opcodes.ASM9) {
                    override fun visit(name: String?, value: Any) { values += value }
                    override fun visitEnum(name: String?, descriptor: String, value: String) { values += value }
                }
            }
            override fun visitEnd() { complete(arguments) }
        }
    }

    companion object {
        const val OPTION = "arc.validationClasspath"
    }
}
