// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class BinaryGeneratedDerivedMetadataLoaderTest {
    @Test
    fun jarRootReusesSingleJarOpenAcrossMultipleColdLookups() {
        val ownerA = ClassId.fromString("demo/Alpha")
        val ownerB = ClassId.fromString("demo/Beta")
        val metadataA =
            GeneratedDerivedMetadata.Derive(
                typeclassId = ClassId.fromString("demo/Show"),
                targetId = ownerA,
            )
        val metadataB =
            GeneratedDerivedMetadata.Derive(
                typeclassId = ClassId.fromString("demo/Eq"),
                targetId = ownerB,
            )
        val jarFile =
            createMetadataJar(
                ownerA to metadataA,
                ownerB to metadataB,
            )

        var openCount = 0
        val root =
            JarBinaryGeneratedDerivedMetadataRoot(jarFile) { file ->
                openCount += 1
                JarFile(file)
            }

        assertEquals(listOf(metadataA), root.generatedMetadataFor(ownerA))
        assertEquals(listOf(metadataB), root.generatedMetadataFor(ownerB))
        assertEquals(1, openCount)
    }

    @Test
    fun loaderReadsGeneratedMetadataFromJarRoots() {
        val owner = ClassId.fromString("demo/User")
        val metadata =
            GeneratedDerivedMetadata.DeriveVia(
                typeclassId = ClassId.fromString("demo/Show"),
                targetId = owner,
                path =
                    listOf(
                        GeneratedDeriveViaPathSegment(
                            kind = GeneratedDeriveViaPathSegment.Kind.WAYPOINT,
                            classId = ClassId.fromString("demo/WireUser"),
                        ),
                    ),
            )
        val jarFile = createMetadataJar(owner to metadata)
        val loader = BinaryGeneratedDerivedMetadataLoader(listOf(jarFile))

        assertEquals(listOf(metadata), loader.generatedMetadataFor(owner))
    }

    private fun createMetadataJar(vararg entries: Pair<ClassId, GeneratedDerivedMetadata>): File {
        val jarFile = Files.createTempFile("typeclass-generated-metadata", ".jar").toFile()
        jarFile.deleteOnExit()
        JarOutputStream(jarFile.outputStream().buffered()).use { jar ->
            entries.forEach { (owner, metadata) ->
                jar.putNextEntry(JarEntry(owner.classFilePathForTest()))
                jar.write(generatedMetadataAnnotatedClassBytes(owner, metadata))
                jar.closeEntry()
            }
        }
        return jarFile
    }
}

private fun generatedMetadataAnnotatedClassBytes(
    owner: ClassId,
    metadata: GeneratedDerivedMetadata,
): ByteArray {
    val writer = ClassWriter(0)
    val internalName = owner.classInternalNameForTest()
    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        internalName,
        null,
        "java/lang/Object",
        null,
    )
    writeGeneratedMetadataAnnotation(writer, metadata)
    writeDefaultConstructor(writer)
    writer.visitEnd()
    return writer.toByteArray()
}

private fun writeGeneratedMetadataAnnotation(
    writer: ClassWriter,
    metadata: GeneratedDerivedMetadata,
) {
    val encoded = metadata.encode()
    writer.visitAnnotation(GENERATED_INSTANCE_ANNOTATION_CLASS_ID.descriptorForTest(), true).apply {
        visit("typeclassId", encoded.typeclassId)
        visit("targetId", encoded.targetId)
        visit("kind", encoded.kind)
        visit("payload", encoded.payload)
        visitEnd()
    }
}

private fun writeDefaultConstructor(writer: ClassWriter) {
    val constructor: MethodVisitor =
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    constructor.visitCode()
    constructor.visitVarInsn(Opcodes.ALOAD, 0)
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    constructor.visitInsn(Opcodes.RETURN)
    constructor.visitMaxs(1, 1)
    constructor.visitEnd()
}

private fun ClassId.classInternalNameForTest(): String =
    classFilePathForTest().removeSuffix(".class")

private fun ClassId.descriptorForTest(): String = "L${classInternalNameForTest()};"

private fun ClassId.classFilePathForTest(): String {
    val packagePath = packageFqName.asString().replace('.', '/')
    val relativePath = relativeClassName.asString().replace('.', '$')
    return if (packagePath.isEmpty()) {
        "$relativePath.class"
    } else {
        "$packagePath/$relativePath.class"
    }
}
