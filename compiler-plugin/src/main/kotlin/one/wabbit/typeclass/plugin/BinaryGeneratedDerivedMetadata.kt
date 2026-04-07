// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.LazyThreadSafetyMode

internal object BinaryGeneratedDerivedMetadataRegistry {
    private val loaders = Collections.synchronizedMap(WeakHashMap<FirSession, BinaryGeneratedDerivedMetadataLoader>())

    fun install(
        session: FirSession,
        loader: BinaryGeneratedDerivedMetadataLoader,
    ) {
        synchronized(loaders) {
            loaders.getOrPut(session) { loader }
        }
    }

    fun generatedMetadataFor(
        session: FirSession,
        classId: ClassId,
    ): List<GeneratedDerivedMetadata> = loaders[session]?.generatedMetadataFor(classId).orEmpty()
}

internal class BinaryGeneratedDerivedMetadataLoader(
    private val classpathRoots: List<File>,
) {
    private val metadataByOwnerId = ConcurrentHashMap<String, List<GeneratedDerivedMetadata>>()
    private val metadataRoots: List<BinaryGeneratedDerivedMetadataRoot> =
        classpathRoots.mapNotNull(::binaryGeneratedDerivedMetadataRoot)

    fun generatedMetadataFor(classId: ClassId): List<GeneratedDerivedMetadata> =
        metadataByOwnerId.computeIfAbsent(classId.asString()) { ownerId ->
            metadataRoots.firstNotNullOfOrNull { root ->
                when (val result = root.generatedMetadataFor(classId)) {
                    BinaryGeneratedDerivedMetadataLookupResult.NotFound -> null
                    is BinaryGeneratedDerivedMetadataLookupResult.Found -> result.metadata
                }
            }.orEmpty()
        }
}

internal sealed interface BinaryGeneratedDerivedMetadataLookupResult {
    data object NotFound : BinaryGeneratedDerivedMetadataLookupResult

    data class Found(
        val metadata: List<GeneratedDerivedMetadata>,
    ) : BinaryGeneratedDerivedMetadataLookupResult
}

internal interface BinaryGeneratedDerivedMetadataRoot {
    fun generatedMetadataFor(classId: ClassId): BinaryGeneratedDerivedMetadataLookupResult
}

internal class DirectoryBinaryGeneratedDerivedMetadataRoot(
    private val root: File,
) : BinaryGeneratedDerivedMetadataRoot {
    override fun generatedMetadataFor(classId: ClassId): BinaryGeneratedDerivedMetadataLookupResult {
        val classFile = root.resolve(classId.classFilePath())
        if (!classFile.isFile) {
            return BinaryGeneratedDerivedMetadataLookupResult.NotFound
        }
        return BinaryGeneratedDerivedMetadataLookupResult.Found(
            parseGeneratedDerivedMetadata(classFile.readBytes(), classId.asString()),
        )
    }
}

internal class JarBinaryGeneratedDerivedMetadataRoot(
    private val root: File,
    private val jarFileOpener: (File) -> JarFile = ::JarFile,
) : BinaryGeneratedDerivedMetadataRoot {
    private val index by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildIndex() }

    override fun generatedMetadataFor(classId: ClassId): BinaryGeneratedDerivedMetadataLookupResult {
        val entryPath = classId.classFilePath()
        if (entryPath !in index.classEntries) {
            return BinaryGeneratedDerivedMetadataLookupResult.NotFound
        }
        return BinaryGeneratedDerivedMetadataLookupResult.Found(
            index.metadataByEntryPath[entryPath].orEmpty(),
        )
    }

    private fun buildIndex(): JarBinaryGeneratedDerivedMetadataIndex {
        val classEntries = linkedSetOf<String>()
        val metadataByEntryPath = linkedMapOf<String, List<GeneratedDerivedMetadata>>()
        jarFileOpener(root).use { jarFile ->
            val entries = jarFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) {
                    continue
                }
                classEntries += entry.name
                val metadata =
                    jarFile.getInputStream(entry).use { stream ->
                        parseGeneratedDerivedMetadata(
                            classBytes = stream.readBytes(),
                            ownerId = entry.name.classEntryOwnerId(),
                        )
                    }
                if (metadata.isNotEmpty()) {
                    metadataByEntryPath[entry.name] = metadata
                }
            }
        }
        return JarBinaryGeneratedDerivedMetadataIndex(
            classEntries = classEntries,
            metadataByEntryPath = metadataByEntryPath,
        )
    }
}

private data class JarBinaryGeneratedDerivedMetadataIndex(
    val classEntries: Set<String>,
    val metadataByEntryPath: Map<String, List<GeneratedDerivedMetadata>>,
)

private fun binaryGeneratedDerivedMetadataRoot(root: File): BinaryGeneratedDerivedMetadataRoot? =
    when {
        root.isDirectory -> DirectoryBinaryGeneratedDerivedMetadataRoot(root)
        root.isFile && root.extension.equals("jar", ignoreCase = true) -> JarBinaryGeneratedDerivedMetadataRoot(root)
        else -> null
    }

private fun parseGeneratedDerivedMetadata(
    classBytes: ByteArray,
    ownerId: String,
): List<GeneratedDerivedMetadata> {
    val entries = mutableListOf<GeneratedDerivedMetadata>()
    val generatedDescriptor = GENERATED_INSTANCE_ANNOTATION_CLASS_ID.descriptor()
    val containerDescriptor = GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID.descriptor()
    ClassReader(classBytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? =
                when (descriptor) {
                    generatedDescriptor -> generatedMetadataAnnotationVisitor(entries, ownerId)
                    containerDescriptor -> generatedMetadataContainerVisitor(entries, ownerId, generatedDescriptor)
                    else -> null
                }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return entries.takeIf(List<GeneratedDerivedMetadata>::isNotEmpty)
        ?: emptyList()
}

private fun generatedMetadataContainerVisitor(
    entries: MutableList<GeneratedDerivedMetadata>,
    ownerId: String,
    generatedDescriptor: String,
): AnnotationVisitor =
    object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visitArray(name: String?): AnnotationVisitor? =
            if (name == "value") {
                object : AnnotationVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(
                        name: String?,
                        descriptor: String,
                    ): AnnotationVisitor? =
                        if (descriptor == generatedDescriptor) {
                            generatedMetadataAnnotationVisitor(entries, ownerId)
                        } else {
                            null
                        }
                }
            } else {
                null
            }
    }

private fun generatedMetadataAnnotationVisitor(
    entries: MutableList<GeneratedDerivedMetadata>,
    ownerId: String,
): AnnotationVisitor =
    object : AnnotationVisitor(Opcodes.ASM9) {
        private var typeclassId: String? = null
        private var targetId: String? = null
        private var kind: String? = null
        private var payload: String? = null

        override fun visit(
            name: String?,
            value: Any?,
        ) {
            when (name) {
                "typeclassId" -> typeclassId = value as? String
                "targetId" -> targetId = value as? String
                "kind" -> kind = value as? String
                "payload" -> payload = value as? String
            }
        }

        override fun visitEnd() {
            val metadata =
                decodeGeneratedDerivedMetadata(
                typeclassId = typeclassId,
                targetId = targetId,
                kind = kind,
                payload = payload,
                expectedOwnerId = ownerId,
            )
            if (metadata != null) {
                entries += metadata
            }
        }
    }

private fun ClassId.classFilePath(): String {
    val packagePath = packageFqName.asString().replace('.', '/')
    val relativePath = relativeClassName.asString().replace('.', '$')
    return if (packagePath.isEmpty()) {
        "$relativePath.class"
    } else {
        "$packagePath/$relativePath.class"
    }
}

private fun ClassId.descriptor(): String = "L${classFilePath().removeSuffix(".class")};"

private fun String.classEntryOwnerId(): String {
    val relativePath = removeSuffix(".class")
    val packageSeparator = relativePath.lastIndexOf('/')
    return if (packageSeparator < 0) {
        relativePath.replace('$', '.')
    } else {
        relativePath.substring(0, packageSeparator + 1) +
            relativePath.substring(packageSeparator + 1).replace('$', '.')
    }
}
