package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.api.map
import ai.enthral.sentinel.data.api.parseInstant
import ai.enthral.sentinel.domain.FamilyLinks
import ai.enthral.sentinel.domain.Guardian
import ai.enthral.sentinel.domain.Invite
import ai.enthral.sentinel.domain.Protectee

interface FamilyRepository {
    suspend fun createInvite(): ApiResult<Invite>
    suspend fun accept(code: String, name: String, fcmToken: String): ApiResult<Unit>
    suspend fun links(): ApiResult<FamilyLinks>
    suspend fun unlink(id: String): ApiResult<Unit>
}

class NetworkFamilyRepository(private val api: SentinelApi, private val session: DeviceSession) : FamilyRepository {
    /** Callers must have shown the explicit consent switch: the API is only ever called with consent = true. */
    override suspend fun createInvite() =
        session.authed { api.createFamilyInvite(consent = true) }.map { Invite(it.code, parseInstant(it.expiresAt)) }

    override suspend fun accept(code: String, name: String, fcmToken: String) =
        session.authed { api.acceptFamilyInvite(code, name, fcmToken) }.map { }

    override suspend fun links() = session.authed { api.getFamilyContacts() }.map { c ->
        FamilyLinks(
            guardians = c.guardians.map { Guardian(it.id, it.name, parseInstant(it.linkedAt)) },
            protecting = c.protecting.map { Protectee(it.id, parseInstant(it.linkedAt)) },
        )
    }

    override suspend fun unlink(id: String) = session.authed { api.unlinkFamily(id) }
}
