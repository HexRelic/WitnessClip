// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

/**
 * The official release signing certificate(s) of the app, as lower-case hex SHA-256 of the
 * certificate (the value `apksigner verify --print-certs` prints, without colons).
 *
 * Android writes the signing certificate of the app that created a key into that key's
 * hardware attestation. The verifier reports the attestation as fully confirmed only if the
 * recording was made by an app signed with one of these certificates, which is what ties a
 * recording to the official, reproducible build. tools/sign-release.sh prints this value.
 */
object Release {
    val SIGNERS: Set<String> = setOf(
        "5888f7dd3fdd8fedb70c0b6bfb7a2e8d7b000d431c0732e687587afa91147eed",
    )

    fun official(signers: List<String>): Boolean = SIGNERS.isEmpty() || signers.any { it.lowercase() in SIGNERS }
}
