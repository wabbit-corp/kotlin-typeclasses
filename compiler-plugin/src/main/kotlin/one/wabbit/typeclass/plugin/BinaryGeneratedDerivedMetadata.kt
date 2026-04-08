// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

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
        val metadata =
            try {
                parseGeneratedDerivedMetadataOrEmpty(classFile.readBytes(), classId.asString())
            } catch (_: IOException) {
                emptyList()
            }
        return BinaryGeneratedDerivedMetadataLookupResult.Found(
            metadata,
        )
    }
}

internal class JarBinaryGeneratedDerivedMetadataRoot(
    private val root: File,
    private val jarFileOpener: (File) -> JarFile = ::JarFile,
) : BinaryGeneratedDerivedMetadataRoot {
    private val lookupByEntryPath = ConcurrentHashMap<String, BinaryGeneratedDerivedMetadataLookupResult>()

    override fun generatedMetadataFor(classId: ClassId): BinaryGeneratedDerivedMetadataLookupResult {
        val entryPath = classId.classFilePath()
        return lookupByEntryPath.computeIfAbsent(entryPath) {
            lookupEntry(
                entryPath = entryPath,
                ownerId = classId.asString(),
            )
        }
    }

    private fun lookupEntry(
        entryPath: String,
        ownerId: String,
    ): BinaryGeneratedDerivedMetadataLookupResult {
        try {
            jarFileOpener(root).use { jarFile ->
                val entry = jarFile.getEntry(entryPath) ?: return BinaryGeneratedDerivedMetadataLookupResult.NotFound
                val metadata =
                    try {
                        jarFile.getInputStream(entry).use { stream ->
                            parseGeneratedDerivedMetadataOrEmpty(
                                classBytes = stream.readBytes(),
                                ownerId = ownerId,
                            )
                        }
                    } catch (_: IOException) {
                        emptyList()
                    }
                return BinaryGeneratedDerivedMetadataLookupResult.Found(metadata)
            }
        } catch (_: IOException) {
            return BinaryGeneratedDerivedMetadataLookupResult.NotFound
        }
    }
}

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

private fun parseGeneratedDerivedMetadataOrEmpty(
    classBytes: ByteArray,
    ownerId: String,
): List<GeneratedDerivedMetadata> =
    try {
        parseGeneratedDerivedMetadata(
            classBytes = classBytes,
            ownerId = ownerId,
        )
    } catch (_: RuntimeException) {
        // Dependency binary scraping must fail closed on malformed classfiles.
        emptyList()
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
