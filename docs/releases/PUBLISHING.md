# Release 发布约定

后续发布采用固定命名，具体功能改动写入发布说明，不加入标题或 APK 文件名。

| 内容 | 格式 | 本次示例 |
| --- | --- | --- |
| Git 标签 | `v<versionName>` | `v1.6.1` |
| GitHub Release 标题 | 与标签完全一致 | `v1.6.1` |
| APK 附件 | `app-release.apk` | `app-release.apk` |
| 完整输出包 | `oplus-network-helper-v<versionName>-release.zip` | `oplus-network-helper-v1.6.1-release.zip` |
| 校验文件 | `SHA256SUMS.txt` | `SHA256SUMS.txt` |
| 发布说明 | `docs/releases/v<versionName>.md` | `docs/releases/v1.6.1.md` |

发布用户指定的已签名 `app/release` 输出，先核对 APK 内嵌版本、架构、签名和 SHA-256。
源码标签指向已推送的对应提交，用户签名的 APK 保持字节不变，不以 Debug 包替代。
ZIP 包含 APK、`output-metadata.json` 和 `baselineProfiles/`，不包含本地构建缓存和凭据。
正文列出本轮实际验证及限制，不沿用历史测试数量。

中英文 README 仅写源码版本、功能和文档，不放 APK、Release 页面或其它安装包下载链接。
安装附件、校验值和版本变更集中维护在 GitHub Release 与对应发布说明中。
保留既有 Release / 标签 / 附件，不为统一命名而改写已发布历史版本。
