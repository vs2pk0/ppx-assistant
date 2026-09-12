# 皮皮虾 6.2.0 功能测试记录

目标：皮皮虾 6.2.0 (620)，Android 16，SukiSU Ultra，LSPosed 模块文件 2.1.1 (7790)，运行时 API 102。助手适配版：26.09.12。

范围为主页（PURITY）、辅助（ASSIST）、杂项（MISC）的 65 个配置项，包括参数项；未测试自动页。真机操作、真实宿主模型验证与服务端结果分别记录，**不表示全部功能都完成了线上端到端验证**。

已完成 10 项本地回归测试和 44 项真实宿主模型检查（最后一轮零失败）。模型检查使用 debug 专用工具，不会随正式 APK 发布。完整限制见各行，尤其是高级弹幕、送神、4K上传和收藏数量的服务端行为。

| 分组 | 功能 | 配置项 | 状态与证据 |
|---|---|---|---|
| PURITY | 图片去水印 | `save_image` | 真机验证：大图保存为 600×600 JPEG；日志确认用显示原图替换下载源。未获得动态 GIF 样本。 |
| PURITY | 视频去水印 | `save_video` | 真机验证：MP4 20,339,289 字节，H.264 1282×720/30fps + AAC，180 秒；修复新 CDN 地址不含 /mp4/ 时的解析异常。 |
| PURITY | 去除广告 | `remove_ads` | 模型与回归验证：非空广告信息被置空后仍正确识别广告，普通内容保留，开屏开关关闭；首页/评论正常。未覆盖全部线上广告投放类型。 |
| PURITY | 去除红点 | `remove_red_dots` | 真机验证：开启时底栏角标隐藏；关闭后与消息99+配合出现角标。范围为频道、底栏、我的页，搜索入口活动提示不在旧功能覆盖范围。 |
| PURITY | 去除头像列表 | `remove_stories` | 真机页面未显示头像列表；宿主模型 getStoryList 返回 null。 |
| PURITY | 屏蔽更新 | `disable_update` | 模型验证：自动检查更新入口被抑制；多次冷启动正常。未模拟服务端下发更新任务。 |
| PURITY | 关闭青少年模式弹窗 | `remove_teenager_dialog` | 模型验证：提示弹窗入口被抑制；多次冷启动未弹窗。 |
| PURITY | 精简分享 | `simplify_share` | 真机验证：分享面板第三方分享项移除，下载后未重新弹分享；视频播放结束页的分享层是宿主独立界面。 |
| PURITY | 屏蔽评论（正则表达式，以\|分割） | `remove_comments` | 真机验证：标记评论可见→关键词过滤后不可见；空回复与普通评论混合模型通过。 |
| PURITY | 关键词 | `remove_comments_keywords` | 真机验证：.*PPX-TEST.* 隐藏测试评论；夹杂错误正则仍有效。 |
| PURITY | 用户名 | `remove_comments_users` | 真机检查用户名过滤后未显示目标测试评论；过滤期间同时测试了本地改名，不能作为独立服务端样本的完整对照。 |
| PURITY | 屏蔽帖子（正则表达式，以\|分割） | `remove_feeds` | 模型验证：普通/关键词/用户名/官方/带货/直播分别判定；无效正则不影响有效规则，空模型保留。 |
| PURITY | 关键词 | `remove_feeds_keywords` | 真实宿主模型验证关键词过滤及错误正则隔离。 |
| PURITY | 用户名 | `remove_feeds_users` | 真实宿主模型验证作者名过滤。 |
| PURITY | 屏蔽官方账号帖子 | `remove_official_feeds` | 真实宿主模型验证官方认证账号过滤；实际投放标签仍依宿主数据。 |
| PURITY | 屏蔽带货帖子 | `remove_promotional_feeds` | 真实宿主模型验证带 promotionInfo 的帖子被过滤。 |
| PURITY | 屏蔽直播帖子 | `remove_live_feeds` | 真实宿主 LiveSaasFeedCell 模型验证过滤。 |
| PURITY | 屏蔽头像挂饰 | `remove_avatar_decoration` | 模型验证：空挂饰、不可变列表、普通/头像挂饰混合通过；修复原越界异常。 |
| PURITY | 关闭浏览历史记录 | `disable_history_items` | 模型验证：历史上报入口直接返回 true；未清除已有历史。 |
| PURITY | 管理频道 | `modify_channels` | 真机验证频道列表按配置生成；模型验证按频道 ID 匹配改名频道。旧配置中的已下线频道可能无内容。 |
| PURITY | 默认频道 | `default_channel` | 真机验证切换默认视频频道；该频道返回空列表，推荐频道可正常加载。模型返回值与配置一致。 |
| ASSIST | 解除下载限制 | `remove_download_restrictions` | 视频与评论许可模型通过；视频实际保存成功。 |
| ASSIST | 个人主页显示注册/出黑屋时间 | `show_register_escape_time` | 真机验证注册时间显示及点击复制；模型验证重复读取不会添加重复项。未遇到实际出黑屋记录。 |
| ASSIST | 保存音频 | `save_audio` | 真机验证：M4A 1,473,250 字节，AAC 44.1kHz 双声道、180 秒。 |
| ASSIST | 复制文字 | `copy_item` | 真机验证帖子和测试评论复制后剪贴板一致；宿主评论/回复/空模型文字提取通过。 |
| ASSIST | 解锁屏蔽词 | `unlock_illegal_words` | 中文与普通表情测试评论发送成功，文字完整；客户端处理不等同于绕过服务端审核。 |
| ASSIST | 解锁高级弹幕特权 | `unlock_danmaku` | 真实宿主权限模型与空权限模型均通过；未发送高级弹幕，服务端接收结果未验证。 |
| ASSIST | 解锁视频亮点功能 | `unlock_highlight` | 设置开关模型通过，视频页面观察到亮点入口；未发送亮点评论或验证图片编辑回调的完整链路。 |
| ASSIST | 解除搜索用户限制 | `unlock_search_user_limits` | 真机验证非本人主页进入搜索、提交关键词并显示搜索分类页；未保证搜索结果完整性。 |
| ASSIST | 解除发帖1080P限制 | `unlock_1080p_limit` | 真实选择器 3840×2160 本地检查通过；未上传高分辨率视频，服务端限制未验证。 |
| ASSIST | 解除收藏表情数限制 | `unlock_emoji_limit` | 真实宿主模型验证线上收藏、本地导入及上限查询，均为 Int.MAX_VALUE；未批量收藏超过原上限。 |
| ASSIST | 解除神评已送满限制 | `unlock_send_god_limit` | 真实宿主模型已送满状态3转为可送状态1；未验证服务端接受再次送神。 |
| ASSIST | 解除楼中楼视频限制 | `unlock_video_comment_limit` | 真机验证楼中楼视频入口显示并打开相册；未上传视频回复。 |
| ASSIST | 划视频防误触 | `prevent_mistouch` | 模型验证 setGestureEnable(true) 后实际字段仍为 false；真机滑动浏览与长按倍速可用。 |
| ASSIST | 查询弹幕发送人 | `query_danmaku_sender` | 真实宿主触摸处理入口+弹幕模型触发后成功跳转本人主页；备用ID字段模型通过。未做真实滚动弹幕坐标双击对照。 |
| ASSIST | 开启母虾提示 | `enable_female_prompt` | 真实宿主模型验证女性名高亮、Spannable 输入兼容、其他用户名不沿用颜色。 |
| ASSIST | 开启双列布局 | `enable_double_layout_style` | 8 个布局设置键分别验证开/关；真机推荐页、个人帖子、插眼页观察到双列。全部/评论/收藏/话题/历史的所有内容类型未逐一覆盖。 |
| ASSIST | 开启点赞音效 | `enable_digg_sound` | 真机验证宿主播放流ID为1；修复旧离线路径失效，改用内置 sound/ 资源并提前加载。 |
| ASSIST | 开启旧版神评样式 | `enable_old_god_icon_style` | 真实宿主 common_god_icon_style=0；未对每种神评徽章做截图对照。 |
| ASSIST | 发帖显示地点标签 | `enable_show_location_label` | 真机验证发帖页显示添加地点并进入地点选择页；未选择地点或发布帖子。 |
| ASSIST | 帖子页脚使用新布局 | `use_feed_footer_new_style` | 真机验证顺序为分享/评论/赞/踩，分享按钮仍可打开面板；移除对不存在的旧实验类的依赖，保留旧宿主入口。 |
| ASSIST | 修改默认交互样式 | `modify_interaction_style` | 真实宿主赞/踩样式读取与配置一致。 |
| ASSIST | 点赞样式 | `digg_style` | 模型验证50，与原设置10进行对照；未逐一截图所有动画。 |
| ASSIST | 点踩样式 | `diss_style` | 模型验证40，与原设置10进行对照；未逐一截图所有动画。 |
| ASSIST | 显示评论具体时间 | `show_exact_comment_time` | 真机测试评论显示到秒，历史时间模型通过。 |
| ASSIST | 近日时间格式（倒序，以\|分割） | `recent_time_format` | 真机验证今天HH点mm分ss秒；未跨日期等待验证所有近日分支。 |
| ASSIST | 之前时间格式 | `exact_time_format` | 真实宿主历史时间按 yyyy-MM-dd HH:mm:ss 输出。 |
| ASSIST | 长按视频保持加速 | `keep_video_play_speed` | 真机验证长按3倍后松手保持，再次长按结束恢复1.5倍；修复普通松手和非目标场景不调用原方法的问题；真机点击重播已恢复播放。 |
| ASSIST | 全局视频倍速 | `normal_play_speed` | 真机播放器实际收到1.5倍参数；测试结束恢复原1.0。 |
| ASSIST | 长按视频倍速 | `pressed_play_speed` | 真机播放器实际收到3.0，页面提示3X快进中；测试结束恢复原2.0。 |
| MISC | 隐藏桌面图标 | `hide_icon` | 调用实际功能完成隐藏/显示图标测试，通过后恢复原组件状态。 |
| MISC | 去除底栏 | `remove_bottom_view` | 真机验证开启后底部导航不显示。 |
| MISC | 去除发布按钮 | `remove_publish_button` | 真机验证主页中央发布按钮隐藏；关闭后恢复并可进入发帖页。 |
| MISC | 去除评论页底栏 | `remove_detail_bottom_view` | 真机验证详情页底栏隐藏；关闭后恢复。 |
| MISC | 开启自定义 | `customize` | 真机验证本地资料显示；按稳定用户ID匹配，避免昵称变化或同名用户误改。关闭后原资料恢复。 |
| MISC | 消息99+ | `modify_message_counts` | 真机底栏出现99+；真实消息响应模型两种计数均改为100。修复错误类名及 long 字段赋值。与去红点同时开时角标可被隐藏。 |
| MISC | 进小黑屋 | `enter_black_house` | 真实本人本地模型包含测试小黑屋标记；未改变账号服务端状态。 |
| MISC | 认证类型 | `certify_type` | 真机验证类型2认证展示；未逐一截图类型1/3。0保持原有认证，不用于删除认证。 |
| MISC | 认证描述 | `certify_desc` | 真机验证本地测试认证描述显示。 |
| MISC | 用户名 | `username` | 真机验证测试昵称显示于我的/个人页，关闭自定义后恢复。 |
| MISC | 个性签名 | `description` | 真机验证本地测试签名显示。 |
| MISC | 获赞 | `like_count` | 真机验证123456显示为12万。 |
| MISC | 粉丝 | `followers_count` | 真机验证23456显示为2.3万。 |
| MISC | 关注 | `following_count` | 真机验证345显示正确。 |
| MISC | 积分 | `point` | 本人本地模型验证4567。 |

测试操作：仅在本人旧帖子发送一条 `PPX-TEST-0912` 评论；测试后已删除，临时点赞已撤销，详情页恢复“还没有评论”。未发布新帖子，未上传视频回复。

测试下载：视频、音频和一张600×600图片保留在手机相册/音乐的 pipixia 目录供核验。下载时间及媒体轨道证据保存在工作区 `build/device/`，此目录不纳入源码版本控制。

关键本地证据：`final-model-results.txt`、`final-footer-order.txt`、`sound-result.json`、`video-tracks.json`、`audio-tracks.json`、`comment-deleted.txt`、`sender-own-profile.txt`。原配置快照为 `preferences-original.json`，交付前已恢复并核对60项完全一致，测试开关已清除。

交付验证：正式版 26.09.12 (20260912) 已覆盖安装，助手显示已激活；皮皮虾冷启动使用 API 102，48 个 Hook 类完成初始化，当前进程 PPX 日志无异常、无调试探针输出。正式 APK 中不存在调试类和私有指令通道。临时测试 APK 已卸载。

最终检查：10/10 单元测试通过；Release 构建通过；lintRelease 为0错误、21条警告；git diff --check通过。Lint 的API错误已修复，剩余警告主要为资源、图标、旧配置兼容和既有界面用法。
