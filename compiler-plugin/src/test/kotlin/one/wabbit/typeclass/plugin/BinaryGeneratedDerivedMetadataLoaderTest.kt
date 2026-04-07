// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class BinaryGeneratedDerivedMetadataLoaderTest {
    @Test
    fun loaderStopsAtFirstClasspathClassfileHitEvenWithoutGeneratedMetadata() {
        val owner = ClassId.fromString("demo/User")
        val metadata =
            GeneratedDerivedMetadata.Derive(
                typeclassId = ClassId.fromString("demo/Show"),
                targetId = owner,
            )
        val shadowingJar = createPlainClassJar(owner)
        val metadataJar = createMetadataJar(owner to metadata)
        val loader = BinaryGeneratedDerivedMetadataLoader(listOf(shadowingJar, metadataJar))

        assertEquals(emptyList(), loader.generatedMetadataFor(owner))
    }

    @Test
    fun jarRootBuildsItsIndexWithASingleJarOpenAndClosesItAfterward() {
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

        val openCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val root =
            JarBinaryGeneratedDerivedMetadataRoot(jarFile) { file ->
                openCount.incrementAndGet()
                CountingJarFile(file) {
                    closeCount.incrementAndGet()
                }
            }

        assertEquals(listOf(metadataA), root.generatedMetadataFor(ownerA).metadataForTest())
        assertEquals(listOf(metadataB), root.generatedMetadataFor(ownerB).metadataForTest())
        assertEquals(1, openCount.get())
        assertEquals(1, closeCount.get())
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

    private fun createPlainClassJar(vararg owners: ClassId): File {
        val jarFile = Files.createTempFile("typeclass-generated-metadata-plain", ".jar").toFile()
        jarFile.deleteOnExit()
        JarOutputStream(jarFile.outputStream().buffered()).use { jar ->
            owners.forEach { owner ->
                jar.putNextEntry(JarEntry(owner.classFilePathForTest()))
                jar.write(plainClassBytes(owner))
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

private fun plainClassBytes(owner: ClassId): ByteArray {
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

private fun BinaryGeneratedDerivedMetadataLookupResult.metadataForTest(): List<GeneratedDerivedMetadata> =
    when (this) {
        BinaryGeneratedDerivedMetadataLookupResult.NotFound -> error("expected classfile hit")
        is BinaryGeneratedDerivedMetadataLookupResult.Found -> metadata
    }

private class CountingJarFile(
    file: File,
    private val onClose: () -> Unit,
) : JarFile(file) {
    override fun close() {
        try {
            super.close()
        } finally {
            onClose()
        }
    }
}
