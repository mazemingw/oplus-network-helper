package com.nvmex.networkhelper.hotspot.utils

 val DefaultRegDomains = listOf(
    RegDomain("US", "美国", "United States", note = "FCC"),
    RegDomain("CA", "加拿大", "Canada", note = "ISED"),
    RegDomain("MX", "墨西哥", "Mexico"),

    RegDomain("CN", "中国", "China"),
    RegDomain("JP", "日本", "Japan"),
    RegDomain("KR", "韩国", "South Korea"),
    RegDomain("SG", "新加坡", "Singapore"),
    RegDomain("MY", "马来西亚", "Malaysia"),
    RegDomain("TH", "泰国", "Thailand"),
    RegDomain("VN", "越南", "Vietnam"),
    RegDomain("PH", "菲律宾", "Philippines"),
    RegDomain("ID", "印度尼西亚", "Indonesia"),
    RegDomain("IN", "印度", "India"),

    RegDomain("HK", "中国香港", "Hong Kong, China"),
    RegDomain("TW", "中国台湾", "Taiwan, China"),
    RegDomain("MO", "中国澳门", "Macao, China"),

    RegDomain("GB", "英国", "United Kingdom", note = "UKCA/Ofcom"),
    RegDomain("DE", "德国", "Germany"),
    RegDomain("FR", "法国", "France"),
    RegDomain("ES", "西班牙", "Spain"),
    RegDomain("IT", "意大利", "Italy"),
    RegDomain("NL", "荷兰", "Netherlands"),
    RegDomain("SE", "瑞典", "Sweden"),
    RegDomain("NO", "挪威", "Norway"),
    RegDomain("CH", "瑞士", "Switzerland"),

    RegDomain("AU", "澳大利亚", "Australia"),
    RegDomain("NZ", "新西兰", "New Zealand"),

    // ——扩展项（不保证所有 ROM 支持）——
    RegDomain("EU", "欧盟（合规域）", "European Union (regulatory domain)", experimental = true),
    RegDomain("00", "世界/通用（可能无效）", "World / generic (may not work)", experimental = true),
)
