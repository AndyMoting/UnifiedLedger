package com.unifiedledger.application

/**
 * P7-04.B production [ImportIntakeIdSource] backed by RFC 9562 UUIDv7 (D-146 Q09-2, spec
 * section 3.2.2; P5-02 product ID discipline).
 *
 * [next] mints exactly the ids the store demands for one intake: the source/evidence/
 * candidate/status-history ids plus [requiredDuplicateIds] groups of duplicate-candidate ids.
 * Lazy materialization is unchanged: [next] runs only inside the winning claim transaction
 * after every typed early rejection, so replays, identity collisions, and losing writers
 * consume no ids (R-Q09-2: the demand is the store's transaction-internal fact, never a
 * caller pre-guess).
 */
class UuidV7ImportIntakeIdSource(
    private val generator: UuidV7Generator,
) : ImportIntakeIdSource {
    override fun next(requiredDuplicateIds: Int): ImportIntakeIds =
        ImportIntakeIds(
            sourceId = ImportSourceId(generator.next()),
            evidenceId = ImportEvidenceId(generator.next()),
            candidateId = ImportCandidateId(generator.next()),
            statusHistoryId = ImportStatusHistoryId(generator.next()),
            duplicateIds =
                List(requiredDuplicateIds) {
                    ImportDuplicateIntakeIds(
                        candidateId = ImportDuplicateCandidateId(generator.next()),
                        statusHistoryId = ImportStatusHistoryId(generator.next()),
                    )
                },
        )
}
