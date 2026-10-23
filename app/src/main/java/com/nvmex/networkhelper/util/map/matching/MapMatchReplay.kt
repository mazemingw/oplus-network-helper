package com.nvmex.networkhelper.util.map.matching

/** Simulated fixes along REAL bundled geometry. Isolated from location, IMU and recording services. */
object MapMatchReplay {
    data class Report(val type: RouteType, val route: String, val gnssMatches: Int, val imuMatches: Int, val recoveryMatches: Int,
        val movementMeters: Double, val results: List<MatchResult>) {
        val passed get() = gnssMatches > 0 && imuMatches >= 4 && movementMeters > 20 && recoveryMatches > 0
    }
    fun run(network: RouteNetwork, type: RouteType): Report {
        val attempts = network.routes.filter { it.type == type && it.length >= 220 }.map { r ->
            val matcher = AutoMapMatcher(network)
            val speed = 6.0
            val results = (0..23).map { i ->
                val p = r.at(30.0 + i * speed)
                val source = if (i in 7..17) MatchSource.IMU else MatchSource.GNSS
                val raw = p.position.copy(lat = p.position.lat + (if (source == MatchSource.IMU) (i - 6) * 0.5 else 0.5) / 111_320)
                matcher.match(MatchFix(raw, 1000L + i * 1000L, source, speed, p.bearing, if (source == MatchSource.IMU) 20.0 else 4.0, true))
            }
            val imu = results.filter { it.fix.source == MatchSource.IMU && it.corrected != null }
            Report(type, r.name + " / " + r.id, results.take(7).count { it.corrected != null }, imu.size,
                results.drop(18).count { it.corrected != null },
                if (imu.size >= 2) routeDistance(imu.first().corrected!!, imu.last().corrected!!) else 0.0, results)
        }
        return attempts.firstOrNull { it.passed } ?: attempts.maxByOrNull { it.imuMatches }
            ?: Report(type, "没有足够长的线路", 0, 0, 0, 0.0, emptyList())
    }
}
