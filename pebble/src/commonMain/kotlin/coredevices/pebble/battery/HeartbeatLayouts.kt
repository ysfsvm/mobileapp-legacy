package coredevices.pebble.battery

/**
 * Heartbeat record layouts the app can decode, keyed by record version and size.
 *
 * To add a firmware whose record differs, run the firmware's
 * `tools/analytics_heartbeat_layout.py <pebbleos.elf>` on its ELF, paste the output verbatim as a
 * new constant below and add it to [known] with the record version (`native.c`).
 */
object HeartbeatLayouts {
    val known: List<HeartbeatLayout> by lazy {
        listOf(
            HeartbeatLayout.parse(version = 3, V3_569),
        ).also { layouts ->
            require(layouts.distinctBy { it.version to it.size }.size == layouts.size) {
                "Two heartbeat layouts share a version and size"
            }
        }
    }

    // PebbleOS v4.47.0 (snowy).
    private const val V3_569 = """
Build ID: 063452d0cc261b25b26e089b5ae74014ec5d11cf

native_heartbeat_record (569 bytes)

Header:
────────────────────────────────────────────────────────────
Offset  Size  Type              Field
────────────────────────────────────────────────────────────
     0     1  unsigned char8    version
     1     8  unsigned64        timestamp
     9    20  char[20]          build_id
────────────────────────────────────────────────────────────

Metrics:
────────────────────────────────────────────────────────────
Offset  Size  Type              Field
────────────────────────────────────────────────────────────
    29     4  unsigned32        memory_pct_max
    33     4  unsigned32        memory_largest_free_pct
    37     4  unsigned32        stack_free_kernel_main_bytes
    41     4  unsigned32        stack_free_kernel_background_bytes
    45     4  unsigned32        stack_free_newtimers_bytes
    49     4  unsigned32        stack_free_app_syscall_bytes
    53     4  unsigned32        stack_free_worker_syscall_bytes
    57     4  signed32          utc_offset_s
    61    33  char[33]          fw_version
    94     4  unsigned32        last_reboot_reason
    98     4  unsigned32        uptime_s
   102     4  unsigned32        battery_soc_pct
   106     2  unsigned16        ↳ scale for battery_soc_pct
   108     4  unsigned32        battery_soc_pct_drop
   112     2  unsigned16        ↳ scale for battery_soc_pct_drop
   114     4  unsigned32        battery_voltage
   118     2  unsigned16        ↳ scale for battery_voltage
   120     4  signed32          battery_voltage_delta
   124     2  unsigned16        ↳ scale for battery_voltage_delta
   126     4  unsigned32        battery_tte_s
   130     4  unsigned32        battery_charge_time_ms
   134     4  unsigned32        battery_discharge_duration_ms
   138     4  unsigned32        backlight_on_time_ms
   142     4  unsigned32        backlight_avg_intensity_pct
   146     4  unsigned32        vibrator_on_time_ms
   150     4  unsigned32        vibrator_avg_strength_pct
   154     4  unsigned32        speaker_on_time_ms
   158     4  unsigned32        speaker_play_count
   162     4  unsigned32        speaker_avg_volume_pct
   166     4  unsigned32        speaker_preempted_count
   170     4  unsigned32        speaker_stream_underrun_count
   174     4  unsigned32        hrm_on_time_ms
   178     4  unsigned32        button_pressed_count
   182     4  unsigned32        touch_event_count
   186     4  unsigned32        gesture_tap_count
   190     4  unsigned32        gesture_double_tap_count
   194     4  unsigned32        touch_driver_wake_cnt
   198     4  unsigned32        cpu_running_pct
   202     2  unsigned16        ↳ scale for cpu_running_pct
   204     4  unsigned32        cpu_sleep0_pct
   208     2  unsigned16        ↳ scale for cpu_sleep0_pct
   210     4  unsigned32        cpu_sleep1_pct
   214     2  unsigned16        ↳ scale for cpu_sleep1_pct
   216     4  unsigned32        cpu_sleep2_pct
   220     2  unsigned16        ↳ scale for cpu_sleep2_pct
   222     4  unsigned32        task_cpu_kernel_main_pct
   226     2  unsigned16        ↳ scale for task_cpu_kernel_main_pct
   228     4  unsigned32        task_cpu_kernel_background_pct
   232     2  unsigned16        ↳ scale for task_cpu_kernel_background_pct
   234     4  unsigned32        task_cpu_worker_pct
   238     2  unsigned16        ↳ scale for task_cpu_worker_pct
   240     4  unsigned32        task_cpu_app_pct
   244     2  unsigned16        ↳ scale for task_cpu_app_pct
   246     4  unsigned32        task_cpu_bt_host_pct
   250     2  unsigned16        ↳ scale for task_cpu_bt_host_pct
   252     4  unsigned32        task_cpu_bt_controller_pct
   256     2  unsigned16        ↳ scale for task_cpu_bt_controller_pct
   258     4  unsigned32        task_cpu_bt_hci_pct
   262     2  unsigned16        ↳ scale for task_cpu_bt_hci_pct
   264     4  unsigned32        task_cpu_new_timers_pct
   268     2  unsigned16        ↳ scale for task_cpu_new_timers_pct
   270     4  unsigned32        task_cpu_idle_pct
   274     2  unsigned16        ↳ scale for task_cpu_idle_pct
   276     4  unsigned32        accel_sample_count
   280     4  unsigned32        accel_shake_count
   284     4  unsigned32        accel_double_tap_count
   288     4  unsigned32        accel_peek_count
   292     4  unsigned32        notification_received_count
   296     4  unsigned32        notification_received_dnd_count
   300     4  unsigned32        phone_call_incoming_count
   304     4  unsigned32        phone_call_time_ms
   308     4  unsigned32        low_power_time_ms
   312     4  unsigned32        stationary_time_ms
   316     4  unsigned32        watchface_time_ms
   320    33  char[33]          watchface_name
   353    40  char[40]          watchface_uuid
   393     4  unsigned32        watchface_crash_count
   397     4  unsigned32        watchface_crash_revert_count
   401     4  unsigned32        pfs_space_free_kb
   405     4  unsigned32        flash_spi_write_bytes
   409     4  unsigned32        flash_spi_erase_bytes
   413     4  unsigned32        ble_adv_short_intvl_time_ms
   417     4  unsigned32        ble_adv_long_intvl_time_ms
   421     4  unsigned32        ble_conn_itvl_min_time_ms
   425     4  unsigned32        ble_conn_itvl_mid_time_ms
   429     4  unsigned32        ble_conn_itvl_max_time_ms
   433     4  unsigned32        ble_disconnect_conn_spvn_tmo_count
   437     4  unsigned32        ble_disconnect_rem_user_term_count
   441     4  unsigned32        ble_disconnect_conn_term_local_count
   445     4  unsigned32        ble_disconnect_lmp_ll_rsp_tmo_count
   449     4  unsigned32        ble_disconnect_conn_establishment_count
   453     4  unsigned32        ble_disconnect_other_count
   457     4  unsigned32        ppog_reversed
   461     4  unsigned32        settings_health_tracking_enabled
   465     4  unsigned32        settings_health_hrm_enabled
   469     4  unsigned32        settings_health_hrm_measurement_interval
   473     4  unsigned32        settings_health_hrm_activity_tracking_enabled
   477     4  unsigned32        settings_motion_sensitivity
   481     4  unsigned32        settings_backlight_intensity_pct
   485     4  unsigned32        settings_backlight_timeout_s
   489     4  unsigned32        settings_touch_enabled
   493     4  unsigned32        app_message_sent_count
   497     4  unsigned32        app_message_received_count
   501     4  unsigned32        app_tick_timer_second_subscribed
   505     4  unsigned32        connectivity_connected_time_ms
   509     4  unsigned32        connectivity_expected_time_ms
   513     4  unsigned32        ble_conn_slave_lat0_time_ms
   517     4  unsigned32        ble_conn_param_update_count
   521     4  unsigned32        accel_stream_recovery_count
   525     4  unsigned32        unexpected_reboot_count
   529     4  signed32          battery_temp_c
   533     2  unsigned16        ↳ scale for battery_temp_c
   535     4  unsigned32        i2c_transfer_error_count
   539     4  unsigned32        ble_conn_itvl_other_time_ms
   543     4  unsigned32        drv_init_fail_flags
   547     4  unsigned32        battery_soc_pct_min
   551     2  unsigned16        ↳ scale for battery_soc_pct_min
   553     4  unsigned32        touch_gated_touchdown_count
   557     4  unsigned32        display_update_count
   561     4  unsigned32        display_flush_time_ms
   565     4  unsigned32        touch_driver_awake_time_ms
────────────────────────────────────────────────────────────
"""
}
