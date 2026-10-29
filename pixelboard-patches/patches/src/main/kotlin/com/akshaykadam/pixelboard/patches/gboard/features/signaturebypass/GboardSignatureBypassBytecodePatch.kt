/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.patches.gboard.features.signaturebypass

import com.akshaykadam.pixelboard.patches.shared.ExternalLabel
import com.akshaykadam.pixelboard.patches.shared.addInstructionsWithLabels
import com.akshaykadam.pixelboard.patches.shared.BytecodePatchContext
import com.akshaykadam.pixelboard.patches.shared.bytecodePatch
import com.akshaykadam.pixelboard.patches.shared.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.akshaykadam.pixelboard.patches.gboard.shared.mutableClass
import com.akshaykadam.pixelboard.patches.gboard.shared.VerifiedTransformationPlan
import com.akshaykadam.pixelboard.patches.gboard.shared.VerifiedTransformationState
import com.akshaykadam.pixelboard.patches.gboard.shared.applyVerified
import com.akshaykadam.pixelboard.patches.gboard.shared.isFieldReference
import com.akshaykadam.pixelboard.patches.gboard.shared.isMethodReference
import com.akshaykadam.pixelboard.patches.gboard.shared.isOpcode
import com.akshaykadam.pixelboard.patches.shared.Constants.COMPATIBILITY_GBOARD

private const val SIGNATURE_UTILS_CLASS = "Lrpv;"
private const val SIGNATURE_UTILS_CLASS_1831 = "Lajpz;"
private const val SIGNATURE_UTILS_CLASS_1831_RELEASE = "Lrvl;"
private const val SIGNATURE_UTILS_CLASS_1841_RELEASE = "Lryi;"
private const val SIGNATURE_UTILS_CLASS_1853_RELEASE = "Lsfr;"

internal val gboardSignatureBypassBytecodePatch = bytecodePatch(
    description = "Let Gboard's own-package signature check pass after re-signing."
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        injectSignatureBypass()
    }
}

context(context: BytecodePatchContext)
private fun injectSignatureBypass() = with(context) {
    val candidates = listOf(
        SIGNATURE_UTILS_CLASS_1853_RELEASE,
        SIGNATURE_UTILS_CLASS_1841_RELEASE,
        SIGNATURE_UTILS_CLASS_1831_RELEASE,
        SIGNATURE_UTILS_CLASS_1831,
        SIGNATURE_UTILS_CLASS,
    )
    val targetClass = candidates.firstOrNull { candidate ->
        mutableClassDefByOrNull(candidate)?.methods?.any(MutableMethod::isExactGboardSignatureBypassTarget) == true
    } ?: error("No matching signature bypass class target found")
    mutableClass(targetClass)
        .methods
        .findGboardSignatureBypassTargetOrThrow()
        .applyGboardSignatureBypass()
}

internal fun Iterable<MutableMethod>.findGboardSignatureBypassTargetOrThrow(): MutableMethod {
    val targets = filter(MutableMethod::isExactGboardSignatureBypassTarget)
    check(targets.size == 1) {
        "Expected exactly one signature bypass target, found ${targets.size}"
    }
    return targets.single()
}

internal fun MutableMethod.applyGboardSignatureBypass() {
    applyVerified(
        VerifiedTransformationPlan(
            targetName = "${definingClass}->$SIGNATURE_CHECK_METHOD_NAME",
            classify = MutableMethod::classifyGboardSignatureBypass,
            mutate = { method ->
                method.addSelfPackagePrologue()
                method
            },
        ),
    )
}

private fun MutableMethod.classifyGboardSignatureBypass(): VerifiedTransformationState {
    check(isExactGboardSignatureBypassTarget()) {
        "Refusing non-target signature bypass method $definingClass->$name"
    }
    val patched = hasSelfPackagePrologue()
    // Structural checks run on the stock body, after our prologue when present.
    val instructions = (implementation?.instructions?.toList()
        ?: error("No instructions available in $definingClass->$name"))
        .drop(if (patched) SELF_PACKAGE_PROLOGUE_SIZE else 0)
    check(implementation!!.registerCount == TARGET_REGISTER_COUNT) {
        "Unexpected register count in $definingClass->$name"
    }

    val returnIndices = instructions.indices.filter { index ->
        instructions[index].isOpcode("RETURN")
    }
    check(returnIndices.size == TARGET_RETURN_REGISTERS.size) {
        "Expected three normal returns in $definingClass->$name"
    }
    val returnRegisters = returnIndices.map { returnIndex ->
        (instructions[returnIndex] as? OneRegisterInstruction)?.registerA
            ?: error("RETURN at $returnIndex has no register")
    }
    check(returnRegisters == TARGET_RETURN_REGISTERS) {
        "Unexpected normal return registers in $definingClass->$name"
    }
    val digestMethod = when (definingClass) {
        SIGNATURE_UTILS_CLASS_1853_RELEASE -> DIGEST_METHOD_DESCRIPTOR_1853_RELEASE
        SIGNATURE_UTILS_CLASS_1841_RELEASE -> DIGEST_METHOD_DESCRIPTOR_1841_RELEASE
        SIGNATURE_UTILS_CLASS_1831_RELEASE -> DIGEST_METHOD_DESCRIPTOR_1831_RELEASE
        SIGNATURE_UTILS_CLASS_1831 -> DIGEST_METHOD_DESCRIPTOR_1831
        else -> DIGEST_METHOD_DESCRIPTOR
    }
    val targetFields = when (definingClass) {
        SIGNATURE_UTILS_CLASS_1853_RELEASE -> TARGET_FIELD_DESCRIPTORS_1853_RELEASE
        SIGNATURE_UTILS_CLASS_1841_RELEASE -> TARGET_FIELD_DESCRIPTORS_1841_RELEASE
        SIGNATURE_UTILS_CLASS_1831_RELEASE -> TARGET_FIELD_DESCRIPTORS_1831_RELEASE
        SIGNATURE_UTILS_CLASS_1831 -> TARGET_FIELD_DESCRIPTORS_1831
        else -> TARGET_FIELD_DESCRIPTORS
    }
    check(instructions.count { it.isMethodReference(digestMethod) } == 1) {
        "Expected exact digest call in $definingClass->$name"
    }
    check(instructions.count { it.isMethodReference(ARRAYS_EQUALS_DESCRIPTOR) } == 1) {
        "Expected exact digest comparison in $definingClass->$name"
    }
    targetFields.forEach { descriptor ->
        check(instructions.count { it.isFieldReference(descriptor) } == 1) {
            "Expected exact field $descriptor in $definingClass->$name"
        }
    }
    TARGET_BASELINE_LITERALS.forEach { expected ->
        check(instructions.any { instruction -> instruction.matchesLiteral(expected) }) {
            "Expected exact literal shape $expected in $definingClass->$name; " +
                "actual=${instructions.mapNotNull { instruction -> instruction.literalShape() }}"
        }
    }

    val oneLiteralCount = instructions.count { instruction ->
        instruction.normalizedOpcode().startsWith("CONST") &&
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 1
    }
    return when {
        patched && oneLiteralCount == TARGET_STOCK_ONE_LITERAL_COUNT ->
            VerifiedTransformationState.PATCHED
        !patched && oneLiteralCount == TARGET_STOCK_ONE_LITERAL_COUNT ->
            VerifiedTransformationState.STOCK
        else -> VerifiedTransformationState.MALFORMED
    }
}

// The re-signed APK fails Gboard's startup check that its own package carries
// Google's certificate. Answer "trusted" only for that own-package question and
// leave every other caller check (e.g. WebDebugBridgeContentProvider) to the
// stock digest comparison, so third-party apps are never treated as Google-signed.
private fun MutableMethod.addSelfPackagePrologue() {
    val firstStock = implementation?.instructions?.firstOrNull()
        ?: error("No instructions available in $definingClass->$name")
    addInstructionsWithLabels(
        0,
        """
        invoke-virtual {p0}, $CONTEXT_GET_PACKAGE_NAME
        move-result-object v0
        invoke-virtual {v0, p1}, $STRING_EQUALS
        move-result v0
        if-eqz v0, :murmur_signature_stock_check
        const/4 v0, 0x1
        return v0
        """.trimIndent(),
        ExternalLabel("murmur_signature_stock_check", firstStock),
    )
}

private fun MutableMethod.hasSelfPackagePrologue(): Boolean {
    val instructions = implementation?.instructions ?: return false
    return instructions.size > 2 &&
        instructions[0].isMethodReference(CONTEXT_GET_PACKAGE_NAME) &&
        instructions[2].isMethodReference(STRING_EQUALS)
}

private fun MutableMethod.isExactGboardSignatureBypassTarget(): Boolean =
    (definingClass == SIGNATURE_UTILS_CLASS ||
        definingClass == SIGNATURE_UTILS_CLASS_1831 ||
        definingClass == SIGNATURE_UTILS_CLASS_1831_RELEASE ||
        definingClass == SIGNATURE_UTILS_CLASS_1841_RELEASE ||
        definingClass == SIGNATURE_UTILS_CLASS_1853_RELEASE) &&
        name == SIGNATURE_CHECK_METHOD_NAME &&
        returnType == "Z" &&
        parameterTypes == SIGNATURE_CHECK_PARAMETERS &&
        accessFlags == TARGET_ACCESS_FLAGS

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.matchesLiteral(
    expected: LiteralShape,
): Boolean = literalShape() == expected

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.literalShape(): LiteralShape? {
    if (!isOpcode("CONST_4")) return null
    val register = (this as? OneRegisterInstruction)?.registerA ?: return null
    val literal = (this as? NarrowLiteralInstruction)?.narrowLiteral ?: return null
    return LiteralShape(register, literal)
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction
    .normalizedOpcode(): String = opcode.name.uppercase().replace('-', '_').replace('/', '_')

private data class LiteralShape(
    val register: Int,
    val literal: Int,
)

private const val SIGNATURE_CHECK_METHOD_NAME = "a"
private const val SIGNATURE_CHECK_DESCRIPTOR =
    "Lrpv;->a(Landroid/content/Context;Ljava/lang/String;)Z"
private val SIGNATURE_CHECK_PARAMETERS =
    listOf("Landroid/content/Context;", "Ljava/lang/String;")
private const val DIGEST_METHOD_DESCRIPTOR =
    "Lrpv;->c(Landroid/content/Context;Ljava/lang/String;)[B"
private const val DIGEST_METHOD_DESCRIPTOR_1831 =
    "Lajpz;->c(Landroid/content/Context;Ljava/lang/String;)[B"
private const val DIGEST_METHOD_DESCRIPTOR_1831_RELEASE =
    "Lrvl;->c(Landroid/content/Context;Ljava/lang/String;)[B"
private const val DIGEST_METHOD_DESCRIPTOR_1841_RELEASE =
    "Lryi;->c(Landroid/content/Context;Ljava/lang/String;)[B"
private const val DIGEST_METHOD_DESCRIPTOR_1853_RELEASE =
    "Lsfr;->c(Landroid/content/Context;Ljava/lang/String;)[B"
private const val ARRAYS_EQUALS_DESCRIPTOR = "Ljava/util/Arrays;->equals([B[B)Z"
private val TARGET_FIELD_DESCRIPTORS = listOf(
    "Lrpv;->e:[B",
    "Lrpv;->d:[B",
    "Lrpv;->c:[B",
    "Lrox;->b:Z",
)
private val TARGET_FIELD_DESCRIPTORS_1831 = listOf(
    "Lajpz;->e:[B",
    "Lajpz;->d:[B",
    "Lajpz;->c:[B",
    "Lajom;->b:Z",
)
private val TARGET_FIELD_DESCRIPTORS_1831_RELEASE = listOf(
    "Lrvl;->e:[B",
    "Lrvl;->d:[B",
    "Lrvl;->c:[B",
    "Lrum;->b:Z",
)
private val TARGET_FIELD_DESCRIPTORS_1841_RELEASE = listOf(
    "Lryi;->e:[B",
    "Lryi;->d:[B",
    "Lryi;->c:[B",
    "Lrxk;->b:Z",
)
private val TARGET_FIELD_DESCRIPTORS_1853_RELEASE = listOf(
    "Lsfr;->e:[B",
    "Lsfr;->d:[B",
    "Lsfr;->c:[B",
    "Lses;->b:Z",
)
private val TARGET_BASELINE_LITERALS = listOf(
    LiteralShape(0, 3),
    LiteralShape(3, 0),
    LiteralShape(4, 1),
    LiteralShape(5, 2),
)
private val TARGET_RETURN_REGISTERS = listOf(6, 4, 3)
private const val TARGET_REGISTER_COUNT = 8
private const val TARGET_STOCK_ONE_LITERAL_COUNT = 1
private const val CONTEXT_GET_PACKAGE_NAME =
    "Landroid/content/Context;->getPackageName()Ljava/lang/String;"
private const val SELF_PACKAGE_PROLOGUE_SIZE = 7
private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"
private val TARGET_ACCESS_FLAGS = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
