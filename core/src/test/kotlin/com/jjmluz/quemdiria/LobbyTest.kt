package com.jjmluz.quemdiria

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyTest {
    private fun Lobby.joined(name: String) = assertIs<JoinResult.Joined>(join(name))

    @Test
    fun joiningGivesEachPlayerAnIdTokenAndColour() {
        val lobby = Lobby()
        val ana = lobby.joined("Ana")
        val rui = lobby.joined("Rui")
        assertEquals(listOf("Ana", "Rui"), lobby.room.value.players.map { it.name })
        assertEquals(listOf(0, 1), lobby.room.value.players.map { it.colour })
        assertTrue(ana.token != rui.token && ana.id != rui.id)
        assertEquals("Ana", lobby.playerFor(ana.token)?.name)
        assertNull(lobby.playerFor("nope"))
    }

    @Test
    fun namesAreTrimmedAndChecked() {
        val lobby = Lobby()
        assertEquals("Maria João", lobby.joined("  Maria   João ").let { lobby.playerFor(it.token)!!.name })
        assertEquals(JoinResult.BadName, lobby.join("   "))
        assertEquals(JoinResult.BadName, lobby.join("A".repeat(Lobby.MAX_NAME + 1)))
        assertIs<JoinResult.Joined>(lobby.join("A".repeat(Lobby.MAX_NAME)))
        assertEquals(JoinResult.NameTaken, lobby.join("maria joão"))
    }

    @Test
    fun theRoomFillsUp() {
        val lobby = Lobby(maxPlayers = 3)
        repeat(3) { lobby.joined("P$it") }
        assertEquals(JoinResult.Full, lobby.join("Late"))
    }

    @Test
    fun aLeavingPlayersColourGoesToTheNextOne() {
        val lobby = Lobby()
        lobby.joined("Ana")
        val rui = lobby.joined("Rui")
        lobby.joined("Eva")
        assertTrue(lobby.leave(rui.token))
        assertFalse(lobby.leave(rui.token))
        lobby.joined("Zé")
        assertEquals(mapOf("Ana" to 0, "Eva" to 2, "Zé" to 1), lobby.room.value.players.associate { it.name to it.colour })
    }

    @Test
    fun renamingKeepsNamesUnique() {
        val lobby = Lobby()
        val ana = lobby.joined("Ana")
        lobby.joined("Rui")
        assertFalse(lobby.rename(ana.token, "RUI"))
        assertFalse(lobby.rename(ana.token, ""))
        assertTrue(lobby.rename(ana.token, "ana"))
        assertEquals("ana", lobby.playerFor(ana.token)?.name)
        assertFalse(lobby.rename("nope", "Eva"))
    }

    @Test
    fun aNewSelfieBumpsTheVersion() {
        val lobby = Lobby()
        val ana = lobby.joined("Ana")
        assertEquals(0, lobby.playerFor(ana.token)!!.selfieVersion)
        lobby.setSelfie(ana.token, byteArrayOf(1))
        lobby.setSelfie(ana.token, byteArrayOf(2))
        val player = lobby.playerFor(ana.token)!!
        assertEquals(2, player.selfieVersion)
        assertEquals(2, player.selfie!!.single().toInt())
    }

    @Test
    fun aPlayerIsConnectedWhileAnyStreamIsOpen() {
        val lobby = Lobby()
        val ana = lobby.joined("Ana")
        assertFalse(lobby.playerFor(ana.token)!!.connected)
        lobby.streamChanged(ana.token, open = true)
        lobby.streamChanged(ana.token, open = true)
        lobby.streamChanged(ana.token, open = false)
        assertTrue(lobby.playerFor(ana.token)!!.connected)
        lobby.streamChanged(ana.token, open = false)
        assertFalse(lobby.playerFor(ana.token)!!.connected)
    }

    @Test
    fun theGameStartsWithEnoughPlayersAndThenNobodyJoins() {
        val lobby = Lobby()
        lobby.joined("Ana")
        lobby.joined("Rui")
        assertFalse(lobby.start())
        lobby.joined("Eva")
        assertTrue(lobby.start())
        assertEquals(Phase.STARTED, lobby.room.value.phase)
        assertEquals(JoinResult.Started, lobby.join("Zé"))
        lobby.backToLobby()
        assertEquals(Phase.LOBBY, lobby.room.value.phase)
        assertEquals(3, lobby.room.value.players.size)
    }
}
