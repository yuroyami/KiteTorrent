package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.settings.BoolSetting
import io.github.yuroyami.kitetorrent.settings.BoolSettingDefaults
import io.github.yuroyami.kitetorrent.settings.ChokingAlgorithm
import io.github.yuroyami.kitetorrent.settings.EncLevel
import io.github.yuroyami.kitetorrent.settings.EncPolicy
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.IntSettingDefaults
import io.github.yuroyami.kitetorrent.settings.MmapWriteMode
import io.github.yuroyami.kitetorrent.settings.ProxyType
import io.github.yuroyami.kitetorrent.settings.SeedChokingAlgorithm
import io.github.yuroyami.kitetorrent.settings.SettingType
import io.github.yuroyami.kitetorrent.settings.Settings
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.settings.StringSetting
import io.github.yuroyami.kitetorrent.settings.StringSettingDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden tests for the settings_pack port. Every expected value is transcribed by
 * hand from libtorrent's settings_pack.hpp (enum / bit layout) and settings_pack.cpp
 * (the str/int/bool default tables).
 */
class SettingsPackTest {

    // ---- bit layout (settings_pack.hpp type_bases) -----------------------------

    @Test
    fun type_bases_match_libtorrent() {
        assertEquals(0x0000, SettingType.STRING_TYPE_BASE)
        assertEquals(0x4000, SettingType.INT_TYPE_BASE)
        assertEquals(0x8000, SettingType.BOOL_TYPE_BASE)
        assertEquals(0xc000, SettingType.TYPE_MASK)
        assertEquals(0x3fff, SettingType.INDEX_MASK)
    }

    @Test
    fun keys_carry_their_type_in_the_top_bits() {
        assertTrue(SettingType.isString(StringSetting.USER_AGENT))
        assertTrue(SettingType.isInt(IntSetting.CONNECTIONS_LIMIT))
        assertTrue(SettingType.isBool(BoolSetting.ENABLE_DHT))

        // a few exact key values (base | index)
        assertEquals(0x0000, StringSetting.USER_AGENT)        // string index 0
        assertEquals(0x4000, IntSetting.TRACKER_COMPLETION_TIMEOUT) // int index 0
        assertEquals(0x8000, BoolSetting.ALLOW_MULTIPLE_CONNECTIONS_PER_IP) // bool index 0
        assertEquals(0x8000 + 62, BoolSetting.ENABLE_DHT)     // bool index 62
        assertEquals(0x4000 + 85, IntSetting.CONNECTIONS_LIMIT) // int index 85
        assertEquals(0x0000 + 10, StringSetting.PEER_FINGERPRINT) // string index 10
    }

    // ---- table sizes (num_*_settings) ------------------------------------------

    @Test
    fun table_sizes_match() {
        // string_types: user_agent..natpmp_gateway, including the deprecated mmap_cache slot
        assertEquals(13, StringSettingDefaults.COUNT)
        assertEquals(StringSettingDefaults.NAMES.size, StringSettingDefaults.DEFAULTS.size)

        // bool_types: index 0..86
        assertEquals(87, BoolSettingDefaults.COUNT)
        assertEquals(BoolSettingDefaults.NAMES.size, BoolSettingDefaults.DEFAULTS.size)

        // int_types: index 0..160
        assertEquals(161, IntSettingDefaults.COUNT)
        assertEquals(IntSettingDefaults.NAMES.size, IntSettingDefaults.DEFAULTS.size)
    }

    // ---- string defaults (str_settings) ----------------------------------------

    @Test
    fun string_defaults_from_cpp() {
        val p = SettingsPack()
        // SET(user_agent, "libtorrent/" LIBTORRENT_VERSION, ...) with version 2.0.12.0
        assertEquals("libtorrent/2.0.12.0", p.getString(StringSetting.USER_AGENT))
        // SET(peer_fingerprint, "-LT20C0-", ...)
        assertEquals("-LT20C0-", p.getString(StringSetting.PEER_FINGERPRINT))
        // SET(listen_interfaces, "0.0.0.0:6881,[::]:6881", ...)
        assertEquals("0.0.0.0:6881,[::]:6881", p.getString(StringSetting.LISTEN_INTERFACES))
        // SET(dht_bootstrap_nodes, "dht.libtorrent.org:25401", ...)
        assertEquals("dht.libtorrent.org:25401", p.getString(StringSetting.DHT_BOOTSTRAP_NODES))
        // SET(announce_ip, nullptr, ...) -> empty string
        assertEquals("", p.getString(StringSetting.ANNOUNCE_IP))
        // SET(proxy_hostname, "", ...)
        assertEquals("", p.getString(StringSetting.PROXY_HOSTNAME))
    }

    // ---- int defaults (int_settings) -------------------------------------------

    @Test
    fun int_defaults_from_cpp() {
        val p = SettingsPack()
        assertEquals(30, p.getInt(IntSetting.TRACKER_COMPLETION_TIMEOUT))
        assertEquals(10, p.getInt(IntSetting.TRACKER_RECEIVE_TIMEOUT))
        assertEquals(1024 * 1024, p.getInt(IntSetting.TRACKER_MAXIMUM_RESPONSE_LENGTH))
        assertEquals(120, p.getInt(IntSetting.PEER_TIMEOUT))
        assertEquals(200, p.getInt(IntSetting.CONNECTIONS_LIMIT))
        assertEquals(8, p.getInt(IntSetting.UNCHOKE_SLOTS_LIMIT))
        assertEquals(0, p.getInt(IntSetting.UPLOAD_RATE_LIMIT))
        assertEquals(0, p.getInt(IntSetting.DOWNLOAD_RATE_LIMIT))
        assertEquals(200, p.getInt(IntSetting.NUM_WANT))
        assertEquals(0x01, p.getInt(IntSetting.PEER_DSCP))
        assertEquals(500, p.getInt(IntSetting.TICK_INTERVAL))
        assertEquals(0x200000, p.getInt(IntSetting.MAX_PIECE_COUNT))
        assertEquals(3 * 1024 * 10240, p.getInt(IntSetting.MAX_METADATA_SIZE))
        assertEquals(4 * 1024 * 204, p.getInt(IntSetting.MAX_HTTP_RECV_BUFFER_SIZE))
        assertEquals(3600, p.getInt(IntSetting.NATPMP_LEASE_DURATION))
        // enum-valued defaults
        assertEquals(ChokingAlgorithm.FIXED_SLOTS_CHOKER, p.getInt(IntSetting.CHOKING_ALGORITHM))
        assertEquals(SeedChokingAlgorithm.ROUND_ROBIN, p.getInt(IntSetting.SEED_CHOKING_ALGORITHM))
        assertEquals(EncPolicy.PE_ENABLED, p.getInt(IntSetting.OUT_ENC_POLICY))
        assertEquals(EncLevel.PE_BOTH, p.getInt(IntSetting.ALLOWED_ENC_LEVEL))
        assertEquals(ProxyType.NONE, p.getInt(IntSetting.PROXY_TYPE))
        assertEquals(MmapWriteMode.AUTO_MMAP_WRITE, p.getInt(IntSetting.DISK_WRITE_MODE))
        // alert_mask default is alert_category::error == 1
        assertEquals(1, p.getInt(IntSetting.ALERT_MASK))
    }

    // ---- bool defaults (bool_settings) -----------------------------------------

    @Test
    fun bool_defaults_from_cpp() {
        val p = SettingsPack()
        assertFalse(p.getBool(BoolSetting.ALLOW_MULTIPLE_CONNECTIONS_PER_IP))
        assertTrue(p.getBool(BoolSetting.SEND_REDUNDANT_HAVE))
        assertTrue(p.getBool(BoolSetting.ENABLE_DHT))
        assertTrue(p.getBool(BoolSetting.ENABLE_LSD))
        assertTrue(p.getBool(BoolSetting.ENABLE_UPNP))
        assertTrue(p.getBool(BoolSetting.ENABLE_NATPMP))
        assertTrue(p.getBool(BoolSetting.ENABLE_OUTGOING_UTP))
        assertTrue(p.getBool(BoolSetting.ENABLE_INCOMING_TCP))
        assertTrue(p.getBool(BoolSetting.USE_PAROLE_MODE))
        assertTrue(p.getBool(BoolSetting.PREFER_UDP_TRACKERS))
        assertFalse(p.getBool(BoolSetting.ANONYMOUS_MODE))
        assertFalse(p.getBool(BoolSetting.PREFER_RC4))
        assertFalse(p.getBool(BoolSetting.ALLOW_IDNA))
        assertTrue(p.getBool(BoolSetting.VALIDATE_HTTPS_TRACKERS))
        assertTrue(p.getBool(BoolSetting.SSRF_MITIGATION))
        assertFalse(p.getBool(BoolSetting.ALLOW_MULTIPLE_CONNECTIONS_PER_PID))
    }

    // ---- override semantics (set_* / has_val / clear) --------------------------

    @Test
    fun override_then_read_back() {
        val p = SettingsPack()
        assertFalse(p.hasVal(IntSetting.CONNECTIONS_LIMIT))
        p.setInt(IntSetting.CONNECTIONS_LIMIT, 500)
        assertTrue(p.hasVal(IntSetting.CONNECTIONS_LIMIT))
        assertEquals(500, p.getInt(IntSetting.CONNECTIONS_LIMIT))

        p.setBool(BoolSetting.ENABLE_DHT, false)
        assertFalse(p.getBool(BoolSetting.ENABLE_DHT))

        p.setString(StringSetting.USER_AGENT, "KiteTorrent/0.1")
        assertEquals("KiteTorrent/0.1", p.getString(StringSetting.USER_AGENT))

        // clearing one restores the default
        p.clear(IntSetting.CONNECTIONS_LIMIT)
        assertFalse(p.hasVal(IntSetting.CONNECTIONS_LIMIT))
        assertEquals(200, p.getInt(IntSetting.CONNECTIONS_LIMIT))

        assertEquals(2, p.size) // enable_dht + user_agent remain
        p.clear()
        assertEquals(0, p.size)
    }

    @Test
    fun wrong_type_accessors_are_safe() {
        val p = SettingsPack()
        // setting an int key via the string setter is a no-op
        p.setString(IntSetting.CONNECTIONS_LIMIT, "nope")
        assertFalse(p.hasVal(IntSetting.CONNECTIONS_LIMIT))
        // reading an int key as a string yields ""
        assertEquals("", p.getString(IntSetting.CONNECTIONS_LIMIT))
        // reading a string key as an int yields 0
        assertEquals(0, p.getInt(StringSetting.USER_AGENT))
        // reading a bool key as a bool of the wrong category is false
        assertFalse(p.getBool(IntSetting.CONNECTIONS_LIMIT))
    }

    // ---- name <-> index round trip (setting_by_name / name_for_setting) --------

    @Test
    fun name_lookup_round_trips() {
        assertEquals(StringSetting.USER_AGENT, SettingsPack.settingByName("user_agent"))
        assertEquals(IntSetting.CONNECTIONS_LIMIT, SettingsPack.settingByName("connections_limit"))
        assertEquals(BoolSetting.ENABLE_DHT, SettingsPack.settingByName("enable_dht"))

        assertEquals("user_agent", SettingsPack.nameForSetting(StringSetting.USER_AGENT))
        assertEquals("connections_limit", SettingsPack.nameForSetting(IntSetting.CONNECTIONS_LIMIT))
        assertEquals("enable_dht", SettingsPack.nameForSetting(BoolSetting.ENABLE_DHT))

        // unknown name -> -1
        assertEquals(-1, SettingsPack.settingByName("does_not_exist"))
        // peer_tos backwards-compat alias maps to peer_dscp
        assertEquals(IntSetting.PEER_DSCP, SettingsPack.settingByName("peer_tos"))
    }

    @Test
    fun every_named_setting_round_trips_by_index() {
        // for each non-deprecated (non-empty) name, settingByName then nameForSetting
        // must return the same name, confirming index alignment across all tables.
        for (name in StringSettingDefaults.NAMES) {
            if (name.isEmpty()) continue
            assertEquals(name, SettingsPack.nameForSetting(SettingsPack.settingByName(name)))
        }
        for (name in IntSettingDefaults.NAMES) {
            if (name.isEmpty()) continue
            assertEquals(name, SettingsPack.nameForSetting(SettingsPack.settingByName(name)))
        }
        for (name in BoolSettingDefaults.NAMES) {
            if (name.isEmpty()) continue
            assertEquals(name, SettingsPack.nameForSetting(SettingsPack.settingByName(name)))
        }
    }

    // ---- default_settings() ----------------------------------------------------

    @Test
    fun defaults_factory_populates_every_int_and_bool() {
        val p = SettingsPack.defaults()
        // int and bool tables have no "no value" entries, so all are present
        assertEquals(IntSettingDefaults.COUNT + BoolSettingDefaults.COUNT + stringsWithDefault(), p.size)
        assertTrue(p.hasVal(IntSetting.CONNECTIONS_LIMIT))
        assertTrue(p.hasVal(BoolSetting.ENABLE_DHT))
        // user_agent has a default, so it is present; announce_ip has none, so absent
        assertTrue(p.hasVal(StringSetting.USER_AGENT))
        assertFalse(p.hasVal(StringSetting.ANNOUNCE_IP))
        // values still equal to the defaults
        assertEquals(200, p.getInt(IntSetting.CONNECTIONS_LIMIT))
        assertEquals("libtorrent/2.0.12.0", p.getString(StringSetting.USER_AGENT))
    }

    @Test
    fun umbrella_settings_alias_equals_category_keys() {
        assertEquals(StringSetting.USER_AGENT, Settings.USER_AGENT)
        assertEquals(IntSetting.CONNECTIONS_LIMIT, Settings.CONNECTIONS_LIMIT)
        assertEquals(BoolSetting.ENABLE_DHT, Settings.ENABLE_DHT)
    }

    private fun stringsWithDefault(): Int =
        StringSettingDefaults.DEFAULTS.count { it != null }
}
