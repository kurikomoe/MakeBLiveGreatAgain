package moe.kuriko.blive.hook

import android.app.AlertDialog
import android.text.InputType
import android.webkit.URLUtil
import android.widget.EditText
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.loggerD
import com.highcapable.yukihookapi.hook.log.loggerE
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject


@InjectYukiHookWithXposed
class Main : IYukiHookXposedInit {
    private val TAG: String = "KurikoBLive"
    private var liveRoomUrl: String? = null
    private var mFloor = "18"

    override fun onInit() = configs {
        debugLog {
            tag = "KurikoBLive"
        }
    }

    override fun onHook() = encase {
        val hookSnippet = { className: String, methodName: String ->
            "com.bilibili.bililive.room.ui.roomv3.LiveRoomActivityV3".hook {
                injectMember {
                    method {
                        name = "onCreate"
                    }
                    afterHook {
                        loggerD(TAG, "LiveRoomActivityV3 hooked")

//                        if (liveRoomUrl.isNullOrEmpty()) {
                        val builder = AlertDialog.Builder(instance())
                        builder.setTitle("请输入流地址（更改任意清晰度后生效）")

                        val input = EditText(instance())
                        input.inputType = InputType.TYPE_CLASS_TEXT
                        builder.setView(input)
                        builder.setPositiveButton("OK") { _, _ ->
                            liveRoomUrl = input.text.toString()
                        }

                        builder.setNegativeButton("Cancel") { dialog, _ ->
                            dialog.cancel()
                        }

                        builder.show()
//                        }

                    }
                }

            }
            className.hook {
                injectMember {
                    method {
                        name = methodName
                    }
                    beforeHook {
                        loggerD(TAG, "hooked")

                        if (liveRoomUrl == null) {
                            return@beforeHook
                        }


                        liveRoomUrl = when (URLUtil.isValidUrl(liveRoomUrl)) {
                            true -> liveRoomUrl!!
                            false -> when (liveRoomUrl!!.startsWith("6")) {
                                true -> "https://live6.kuriko.moe/live/%s.flv".format(
                                    liveRoomUrl!!.substring(
                                        1
                                    )
                                )

                                false -> "https://live.kuriko.moe/live/%s.flv".format(liveRoomUrl!!)
                            }
                        }

                        loggerD(TAG, liveRoomUrl!!)

                        val pat = "((https?://)?[^/]+)(/[^?]+)(\\?(.+))?".toRegex()
                        val matchResult = pat.matchEntire(liveRoomUrl!!) ?: return@beforeHook

                        // Reset liveRoomUrl
                        // liveRoomUrl = "";

                        val newHost = matchResult.groupValues[1]
                        val newBaseUrl = matchResult.groupValues[3]
                        val newExtra = matchResult.groupValues.getOrElse(5) { "" }


                        loggerD(TAG, "as provided, base_url = $newBaseUrl, host = $newHost")

                        val playUrlInfo = this.args[0]
                        loggerD(TAG, playUrlInfo.toString())

                        val playUrl = XposedHelpers.getObjectField(playUrlInfo, "Playurl")
                        val streams =
                            XposedHelpers.getObjectField(playUrl, "Streams") as ArrayList<*>

                        for (stream in streams) {
                            val protocolName =
                                XposedHelpers.getObjectField(stream, "ProtocolName") as String
                            val formats =
                                XposedHelpers.getObjectField(stream, "formats") as ArrayList<*>

                            loggerD(TAG, "protocolName: $protocolName")

                            for (format in formats) {
                                val formatName = XposedHelpers.getObjectField(
                                    format,
                                    "FormatName"
                                ) as String
                                val codecs = XposedHelpers.getObjectField(
                                    format,
                                    "Codecs"
                                ) as ArrayList<*>

                                loggerD(TAG, "FormatName: $formatName")

                                for (codec in codecs) {
                                    val baseUrl = XposedHelpers.getObjectField(
                                        codec,
                                        "BaseUrl"
                                    ) as String
                                    val codecName = XposedHelpers.getObjectField(
                                        codec,
                                        "CodecName"
                                    ) as String

                                    XposedHelpers.setObjectField(codec, "BaseUrl", newBaseUrl)

                                    loggerD(TAG, "codecName: $codecName, baseUrl: $baseUrl")

                                    val urls = XposedHelpers.getObjectField(
                                        codec,
                                        "Urls"
                                    ) as ArrayList<*>

                                    for (url in urls) {
                                        val host =
                                            XposedHelpers.getObjectField(url, "Host") as String
                                        val extra = XposedHelpers.getObjectField(
                                            url,
                                            "Extra"
                                        ) as String
                                        val streamTTL =
                                            XposedHelpers.getLongField(url, "stream_ttl")

                                        XposedHelpers.setObjectField(url, "Host", newHost)
                                        XposedHelpers.setObjectField(url, "Extra", newExtra)
                                        XposedHelpers.setLongField(
                                            url,
                                            "stream_ttl",
                                            9999999999
                                        )

                                        loggerD(
                                            TAG,
                                            "host: $host, extra: $extra, streamTTL: $streamTTL"
                                        )
                                    }
                                }

                            }

                            loggerD(TAG, protocolName)
                        }

                    }
                }
            }
        }

        val hookData = { className: String ->
            className.hook {
                injectMember {
                    method {
                        name = "isMobileActive"
                    }
                    afterHook {
                        val oldResult = result
                        this.result = true
                        loggerD(TAG, "isMobileActive hooked: return value = $oldResult -> $result")
                    }
                }

                val func = { it: HookParam ->
                    val oldResult = it.result
                    it.result = 2
                    loggerD(TAG, "getNetwork hooked: return value = $oldResult -> ${it.result}")
                }

                injectMember {
                    method {
                        name = "getNetwork"
                    }
                    afterHook { func(this) }
                }

                injectMember {
                    method {
                        name = "getNetworkWithoutCache"
                    }
                    afterHook { func(this) }
                }

                injectMember {
                    method {
                        name = "isWifiActive"
                    }
                    afterHook { this.result = false; }
                }
            }
        }

        val hookConnectivity = { className: String ->
            className.hook {
                injectMember {
                    method {
                        name = "isConnectedMobile"
                    }
                    afterHook { this.result = true; }
                }
                injectMember {
                    method {
                        name = "isConnectedWifi"
                    }
                    afterHook { this.result = false; }
                }
            }
        }

        val hookFreeDataTypeSnippet = { className: String ->
            className.hook {
                injectMember {
                    method {
                        name = "getType"
                    }
                    afterHook {
                        loggerD(TAG, "Current FreeData Type = $result")
                    }
                }
            }
        }

        val hookComboDM = { className: String, methodName: String ->
            className.hook {
                injectMember {
                    method {
                        name = methodName
                    }
                    replaceUnit {
                        loggerD(TAG, "comboDM Replaced")
                    }
                }
            }
        }

        val modifyExtra = fun(extra_string: String): Pair<String, String> {
            val extra_dict = JSONObject(extra_string)

            val normal_dm = 1
            val bottom_dm = 4

            val cur_dm_mode = try {
                extra_dict.getInt("player_mode")
            } catch (e: Exception) {
                normal_dm
            }
            extra_dict.put("player_mode", normal_dm)

            // Modify content
            val content = extra_dict.getString("content")
            val prefixContent = when (cur_dm_mode) {
                // normal_dm -> "[普通弹幕]"
                normal_dm -> ""
                bottom_dm -> "[底部弹幕]"
                else -> "[未知($cur_dm_mode)]"
            }
            val new_content = "$prefixContent $content"
            extra_dict.put("content", new_content)

            // Disable emotion
            extra_dict.put("dm_type", 0)
            extra_dict.put("emoticon_unique", "")

            return Pair(new_content, extra_dict.toString())
        }

        val modifyDanmakuData = fun(danmaku_msg_string: String): JSONObject {
            val j = JSONObject(danmaku_msg_string)

            try {
                if (j.getString("cmd") == "DANMU_MSG") {
                    val info = j.getJSONArray("info")
                    val attrs = info.getJSONArray(0)

                    val extra_idx = 15
                    val extra = attrs.getJSONObject(extra_idx)

                    val extra_string = extra.getString("extra")

                    val (new_content, new_extra_string) = modifyExtra(extra_string)

                    extra.put("extra", new_extra_string)

                    attrs.put(18, new_content);


                    // 底部弹幕栗子
                    // this.args[1] = "{\"cmd\":\"DANMU_MSG\",\"info\":[[0,4,25,14893055,1707402083231,-1848288017,0,\"7cbfe330\",0,0,5,\"#1453BAFF,#4C2263A2,#3353BAFF\",1,{\"bulge_display\":1,\"emoticon_unique\":\"room_6136246_1950\",\"height\":162,\"in_player_area\":1,\"is_dynamic\":1,\"url\":\"http:\\/\\/i0.hdslb.com\\/bfs\\/garb\\/cee5358109a2d74670c41832cee7357e73302e86.png\",\"width\":162},\"{}\",{\"mode\":0,\"show_player_type\":0,\"extra\":\"{\\\"send_from_me\\\":false,\\\"mode\\\":0,\\\"color\\\":14893055,\\\"dm_type\\\":1,\\\"font_size\\\":25,\\\"player_mode\\\":4,\\\"show_player_type\\\":0,\\\"content\\\":\\\"大笑\\\",\\\"user_hash\\\":\\\"2092950320\\\",\\\"emoticon_unique\\\":\\\"room_6136246_1950\\\",\\\"bulge_display\\\":1,\\\"recommend_score\\\":0,\\\"main_state_dm_color\\\":\\\"\\\",\\\"objective_state_dm_color\\\":\\\"\\\",\\\"direction\\\":0,\\\"pk_direction\\\":0,\\\"quartet_direction\\\":0,\\\"anniversary_crowd\\\":0,\\\"yeah_space_type\\\":\\\"\\\",\\\"yeah_space_url\\\":\\\"\\\",\\\"jump_to_url\\\":\\\"\\\",\\\"space_type\\\":\\\"\\\",\\\"space_url\\\":\\\"\\\",\\\"animation\\\":{},\\\"emots\\\":null,\\\"is_audited\\\":false,\\\"id_str\\\":\\\"7f072256e9b0443a4ddb243f3965c4e385\\\",\\\"icon\\\":null,\\\"show_reply\\\":true,\\\"reply_mid\\\":0,\\\"reply_uname\\\":\\\"\\\",\\\"reply_uname_color\\\":\\\"\\\",\\\"reply_is_mystery\\\":false,\\\"hit_combo\\\":0}\",\"user\":{\"uid\":480038049,\"base\":{\"name\":\"sinction\",\"face\":\"https:\\/\\/i0.hdslb.com\\/bfs\\/face\\/5ac63ac58fd2ce1e7cfd63ac0c7163c2366d5f2f.webp\",\"name_color\":0,\"is_mystery\":false,\"risk_ctrl_info\":null,\"origin_info\":{\"name\":\"sinction\",\"face\":\"https:\\/\\/i0.hdslb.com\\/bfs\\/face\\/5ac63ac58fd2ce1e7cfd63ac0c7163c2366d5f2f.webp\"},\"official_info\":{\"role\":0,\"title\":\"\",\"desc\":\"\",\"type\":-1}},\"medal\":null,\"wealth\":null,\"title\":{\"old_title_css_id\":\"\",\"title_css_id\":\"\"},\"guard\":null,\"uhead_frame\":null,\"guard_leader\":{\"is_guard_leader\":false}}},{\"activity_identity\":\"\",\"activity_source\":0,\"not_show\":0},43],\"大笑\",[480038049,\"sinction\",0,0,0,10000,1,\"#00D1F1\"],[23,\"凉呆皮\",\"凉哈皮\",6136246,1725515,\"\",0,6809855,1725515,5414290,3,1,8618005],[21,0,5805790,\">50000\",0],[\"\",\"\"],0,3,null,{\"ts\":1707402083,\"ct\":\"5A070948\"},0,0,null,null,0,105,[16],null],\"dm_v2\":\"\"}";
                    // this.args[1] = "{\"cmd\":\"DANMU_MSG\",\"info\":[[0,4,25,5816798,1707402142880,1707391882,0,\"d27bc482\",0,0,0,\"\",0,\"{}\",\"{}\",{\"mode\":0,\"show_player_type\":0,\"extra\":\"{\\\"send_from_me\\\":false,\\\"mode\\\":0,\\\"color\\\":5816798,\\\"dm_type\\\":0,\\\"font_size\\\":25,\\\"player_mode\\\":1,\\\"show_player_type\\\":0,\\\"content\\\":\\\"后面有车的，不知道什么时候出\\\",\\\"user_hash\\\":\\\"3531326594\\\",\\\"emoticon_unique\\\":\\\"\\\",\\\"bulge_display\\\":0,\\\"recommend_score\\\":1,\\\"main_state_dm_color\\\":\\\"\\\",\\\"objective_state_dm_color\\\":\\\"\\\",\\\"direction\\\":0,\\\"pk_direction\\\":0,\\\"quartet_direction\\\":0,\\\"anniversary_crowd\\\":0,\\\"yeah_space_type\\\":\\\"\\\",\\\"yeah_space_url\\\":\\\"\\\",\\\"jump_to_url\\\":\\\"\\\",\\\"space_type\\\":\\\"\\\",\\\"space_url\\\":\\\"\\\",\\\"animation\\\":{},\\\"emots\\\":null,\\\"is_audited\\\":false,\\\"id_str\\\":\\\"7a307ce57e01d3981d84a6c62865c4e331\\\",\\\"icon\\\":null,\\\"show_reply\\\":true,\\\"reply_mid\\\":0,\\\"reply_uname\\\":\\\"\\\",\\\"reply_uname_color\\\":\\\"\\\",\\\"reply_is_mystery\\\":false,\\\"hit_combo\\\":0}\",\"user\":{\"uid\":82244740,\"base\":{\"name\":\"树下人影月下积霜\",\"face\":\"https:\\/\\/i1.hdslb.com\\/bfs\\/face\\/62a5eede66d2bdff9a858aa2edc1df4d14c2a48a.jpg\",\"name_color\":0,\"is_mystery\":false,\"risk_ctrl_info\":null,\"origin_info\":{\"name\":\"树下人影月下积霜\",\"face\":\"https:\\/\\/i1.hdslb.com\\/bfs\\/face\\/62a5eede66d2bdff9a858aa2edc1df4d14c2a48a.jpg\"},\"official_info\":{\"role\":0,\"title\":\"\",\"desc\":\"\",\"type\":-1}},\"medal\":null,\"wealth\":null,\"title\":{\"old_title_css_id\":\"\",\"title_css_id\":\"\"},\"guard\":null,\"uhead_frame\":null,\"guard_leader\":{\"is_guard_leader\":false}}},{\"activity_identity\":\"\",\"activity_source\":0,\"not_show\":0},0],\"后面有车的，不知道什么时候出\",[82244740,\"树下人影月下积霜\",0,0,0,10000,1,\"\"],[20,\"大母鹅\",\"EdmundDZhang\",5050,13081892,\"\",0,13081892,13081892,13081892,0,1,433351],[14,0,6406234,\">50000\",0],[\"\",\"\"],0,0,null,{\"ts\":1707402142,\"ct\":\"AC1C103F\"},0,0,null,null,0,63,[8],null],\"dm_v2\":\"\"}";
                    // player_mode: 1: 普通弹幕,  4 底端弹幕
                    // this.args[1] = "{\"cmd\":\"DANMU_MSG\",\"info\":[[0,4,25,5816798,1707402142880,1707391882,0,\"d27bc482\",0,0,0,\"\",0,\"{}\",\"{}\",{\"mode\":0,\"show_player_type\":0,\"extra\":\"{\\\"send_from_me\\\":false,\\\"mode\\\":0,\\\"color\\\":5816798,\\\"dm_type\\\":0,\\\"font_size\\\":25,\\\"player_mode\\\":4,\\\"show_player_type\\\":0,\\\"content\\\":\\\"后面有车的，不知道什么时候出\\\",\\\"user_hash\\\":\\\"3531326594\\\",\\\"emoticon_unique\\\":\\\"\\\",\\\"bulge_display\\\":0,\\\"recommend_score\\\":1,\\\"main_state_dm_color\\\":\\\"\\\",\\\"objective_state_dm_color\\\":\\\"\\\",\\\"direction\\\":0,\\\"pk_direction\\\":0,\\\"quartet_direction\\\":0,\\\"anniversary_crowd\\\":0,\\\"yeah_space_type\\\":\\\"\\\",\\\"yeah_space_url\\\":\\\"\\\",\\\"jump_to_url\\\":\\\"\\\",\\\"space_type\\\":\\\"\\\",\\\"space_url\\\":\\\"\\\",\\\"animation\\\":{},\\\"emots\\\":null,\\\"is_audited\\\":false,\\\"id_str\\\":\\\"7a307ce57e01d3981d84a6c62865c4e331\\\",\\\"icon\\\":null,\\\"show_reply\\\":true,\\\"reply_mid\\\":0,\\\"reply_uname\\\":\\\"\\\",\\\"reply_uname_color\\\":\\\"\\\",\\\"reply_is_mystery\\\":false,\\\"hit_combo\\\":0}\",\"user\":{\"uid\":82244740,\"base\":{\"name\":\"树下人影月下积霜\",\"face\":\"https:\\/\\/i1.hdslb.com\\/bfs\\/face\\/62a5eede66d2bdff9a858aa2edc1df4d14c2a48a.jpg\",\"name_color\":0,\"is_mystery\":false,\"risk_ctrl_info\":null,\"origin_info\":{\"name\":\"树下人影月下积霜\",\"face\":\"https:\\/\\/i1.hdslb.com\\/bfs\\/face\\/62a5eede66d2bdff9a858aa2edc1df4d14c2a48a.jpg\"},\"official_info\":{\"role\":0,\"title\":\"\",\"desc\":\"\",\"type\":-1}},\"medal\":null,\"wealth\":null,\"title\":{\"old_title_css_id\":\"\",\"title_css_id\":\"\"},\"guard\":null,\"uhead_frame\":null,\"guard_leader\":{\"is_guard_leader\":false}}},{\"activity_identity\":\"\",\"activity_source\":0,\"not_show\":0},0],\"后面有车的，不知道什么时候出\",[82244740,\"树下人影月下积霜\",0,0,0,10000,1,\"\"],[99,\"大母鹅\",\"EdmundDZhang\",5050,13081892,\"\",0,13081892,13081892,13081892,0,1,433351],[14,0,6406234,\">50000\",0],[\"\",\"\"],0,0,null,{\"ts\":1707402142,\"ct\":\"AC1C103F\"},0,0,null,null,0,63,[8],null],\"dm_v2\":\"\"}";
                }
            } catch (e: Exception) {
                loggerE(TAG, e.toString())
            }

            return j
        }

        val hookLiveDanmaku = { className: String ->
            className.hook {
                injectMember {
                    constructor {
                        paramCount(2)
                    }

                    beforeHook {
                        this.args[1] = modifyDanmakuData(this.args[1] as String).toString()
                    }
                }

            }
        }

        val hookLiveDanmakuObserver = {
            className: String -> className.hook {
                injectMember {
                    method {
                        name = "invoke"
                    }
                    beforeHook {
                        loggerE(TAG, "1: ${this.args[1]}")
                        loggerE(TAG, "2: ${this.args[2]}")
                        if (this.args.size == 4) {
                            this.args[1] = modifyDanmakuData(this.args[1].toString())
                            this.args[2] = modifyDanmakuData(this.args[2].toString())
                        } else {
                            this.args[1] = modifyDanmakuData(this.args[1].toString())
                        }
                    }
                }
            }
        }

        val hookAnotherDanmaku = {
            "com.bilibili.bililive.room.ui.roomv3.danmaku.effect.model.LiveChronosDanmaku\$Dms".hook {
                injectMember {
                    method {
                        name = "setExtra"
                    }
                    beforeHook {
                        loggerD(TAG, "0: ${this.args[0].toString()}")
                        val (_, new_extra_string) = modifyExtra(this.args[0] as String)
                        this.args[0] = new_extra_string
                    }
                }
            }
        }

        // XposedHelpers.findAndHookMethod("com.bilibili.bililive.infra.socketbuilder.inline.danmaku.LiveInlineDanmakuParser$observeOriginDanmaku$$inlined$observeOriginMessageOnUiThread$1", classLoader, "invoke", java.lang.String.class, org.json.JSONObject.class, org.json.JSONObject.class, int[].class, new XC_MethodHook() {
        //    @Override
        //    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        //        super.beforeHookedMethod(param);
        //    }
        //    @Override
        //    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        //        super.afterHookedMethod(param);
        //    }
        //});
        loadApp(name = "tv.danmaku.bilibilihd") {
            hookAnotherDanmaku()
//            hookSnippet(
//                "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.b",
//                "b"
//            );
//            hookData(
//                "com.bilibili.base.connectivity.ConnectivityMonitor",
//            );
//            hookConnectivity(
//                "com.bilibili.base.connectivity.Connectivity",
//            );
//            hookFreeDataTypeSnippet(
//                "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.LiveUrlFreeType",
//            );
//            hookLiveDanmakuObserver("com.bilibili.bililive.infra.socketbuilder.inline.danmaku.LiveInlineDanmakuParser\$observeOriginDanmaku\$1")
        }
        loadApp(name = "tv.danmaku.bili") {
            hookComboDM(
                "com.bilibili.bililive.room.biz.combodm.LiveRoomComboCardBizServiceImpl",
                "ed"
            )
            hookComboDM(
                "com.bilibili.bililive.room.biz.combodm.LiveRoomComboCardBizServiceImpl",
                "cd"
            )
            hookLiveDanmaku("com.bilibili.bililive.infra.socket.core.codec.msg.c")
//            hookSnippet(
//                "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.d",
//                "a"
//            );
//            hookData(
//                "com.bilibili.base.connectivity.ConnectivityMonitor",
//            );
//            hookFreeDataTypeSnippet(
//                "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.LiveUrlFreeType",
//            );
        }
        loadApp(name = "com.bilibili.app.in") {
//            hookSnippet(
//                "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.a",
//                "b"
//            );
//            hookData(
//                "com.bilibili.base.connectivity.ConnectivityMonitor",
//            );
//            hookFreeDataTypeSnippet(
//                "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.LiveUrlFreeType",
//            );
            hookLiveDanmakuObserver("com.bilibili.bililive.room.biz.interaction.LiveRoomInteractionBizServiceImpl\$observeSocketMessage\$\$inlined\$observeOriginMessageOnUiThread\$1")
        }

    }
}