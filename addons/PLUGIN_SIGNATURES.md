# Signed plugin repository metadata (Phase 3 security primitive)

BeatRaxus plugin repositories contain declarative JSON only. This phase adds a detached
RSA/SHA-256 signature verifier. It does **not** download or execute plugin code.

## Trust model

A signature is meaningful only when the public key is already trusted locally. Do not trust a
public key merely because it appears beside a signature in the same downloaded repository file.
The application must obtain the publisher's key fingerprint through an independent trusted
channel, show it to the user, and pin the confirmed key before accepting signed metadata.

`PluginRepositorySignature.verify(...)` accepts the exact UTF-8 payload bytes, detached Base64
signature, Base64 X.509 SubjectPublicKeyInfo RSA public key, and the independently pinned key.
It returns `false` for invalid signatures, malformed inputs, or key mismatch.
`fingerprint(...)` returns an uppercase SHA-256 fingerprint grouped in four-hex-character blocks.

## Publisher signing procedure

1. Serialize the repository JSON payload once and save those exact bytes as `repository-payload.json`.
2. Sign those exact bytes with an RSA private key using `SHA256withRSA`.
3. Publish the payload and detached Base64 signature separately.
4. Distribute the public key fingerprint out-of-band (for example, on the publisher's verified
   project website or a signed release announcement).
5. The app must pin the key only after the user confirms that fingerprint.

Do not reformat, re-indent, reorder, or otherwise modify the payload after signing: verification
uses the exact bytes.

## Important integration boundary

This patch adds and tests the cryptographic verifier, but it does not yet change
`PluginRepositoryManager` to require signatures or add a key-pinning screen. Existing unsigned
repositories therefore continue to behave as before. Treat unsigned repositories as unverified.
The next integration step is to add a user-facing fingerprint confirmation flow and enforce the
pinned key during both repository refresh and plugin-manifest installation. Do not describe this
primitive alone as complete publisher authentication.
