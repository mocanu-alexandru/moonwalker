package com.alexmcn.moonwalker

/**
 * Capitalele lumii (membri ONU + câteva observatoare), pentru modul „TUR CAPITALE".
 * Coordonate aproximative ale centrului fiecărei capitale (lat, lon, în grade).
 * Folosite ca waypoint-uri: serviciul le ordonează nearest-first (haversine) + 2-opt și
 * conduce CONTINUU de la una la alta (fără teleport), la viteza configurată.
 */
object Capitals {
    data class Cap(val name: String, val lat: Double, val lon: Double) {
        fun coord() = doubleArrayOf(lat, lon)
    }

    val all: List<Cap> = listOf(
        // ── Europa ───────────────────────────────────────────────────────────
        Cap("Tirana", 41.33, 19.82), Cap("Andorra la Vella", 42.51, 1.52),
        Cap("Viena", 48.21, 16.37), Cap("Minsk", 53.90, 27.57),
        Cap("Bruxelles", 50.85, 4.35), Cap("Sarajevo", 43.85, 18.36),
        Cap("Sofia", 42.70, 23.32), Cap("Zagreb", 45.81, 15.98),
        Cap("Praga", 50.08, 14.44), Cap("Copenhaga", 55.68, 12.57),
        Cap("Tallinn", 59.44, 24.75), Cap("Helsinki", 60.17, 24.94),
        Cap("Paris", 48.85, 2.35), Cap("Berlin", 52.52, 13.40),
        Cap("Atena", 37.98, 23.73), Cap("Budapesta", 47.50, 19.04),
        Cap("Reykjavik", 64.15, -21.94), Cap("Dublin", 53.35, -6.26),
        Cap("Roma", 41.90, 12.50), Cap("Pristina", 42.66, 21.16),
        Cap("Riga", 56.95, 24.11), Cap("Vaduz", 47.14, 9.52),
        Cap("Vilnius", 54.69, 25.28), Cap("Luxemburg", 49.61, 6.13),
        Cap("Valletta", 35.90, 14.51), Cap("Chișinău", 47.01, 28.86),
        Cap("Monaco", 43.74, 7.42), Cap("Podgorica", 42.44, 19.26),
        Cap("Amsterdam", 52.37, 4.90), Cap("Skopje", 41.99, 21.43),
        Cap("Oslo", 59.91, 10.75), Cap("Varșovia", 52.23, 21.01),
        Cap("Lisabona", 38.72, -9.14), Cap("București", 44.43, 26.10),
        Cap("Moscova", 55.76, 37.62), Cap("San Marino", 43.94, 12.45),
        Cap("Belgrad", 44.79, 20.45), Cap("Bratislava", 48.15, 17.11),
        Cap("Ljubljana", 46.05, 14.51), Cap("Madrid", 40.42, -3.70),
        Cap("Stockholm", 59.33, 18.07), Cap("Berna", 46.95, 7.45),
        Cap("Kiev", 50.45, 30.52), Cap("Londra", 51.51, -0.13),
        Cap("Vatican", 41.90, 12.45),

        // ── Asia ─────────────────────────────────────────────────────────────
        Cap("Kabul", 34.53, 69.17), Cap("Erevan", 40.18, 44.51),
        Cap("Baku", 40.41, 49.87), Cap("Manama", 26.23, 50.59),
        Cap("Dhaka", 23.81, 90.41), Cap("Thimphu", 27.47, 89.64),
        Cap("Bandar Seri Begawan", 4.89, 114.94), Cap("Phnom Penh", 11.56, 104.92),
        Cap("Beijing", 39.90, 116.41), Cap("Tbilisi", 41.72, 44.79),
        Cap("New Delhi", 28.61, 77.21), Cap("Jakarta", -6.21, 106.85),
        Cap("Teheran", 35.69, 51.39), Cap("Bagdad", 33.31, 44.36),
        Cap("Ierusalim", 31.77, 35.21), Cap("Tokyo", 35.68, 139.69),
        Cap("Amman", 31.95, 35.93), Cap("Astana", 51.17, 71.43),
        Cap("Kuwait City", 29.38, 47.99), Cap("Bișkek", 42.87, 74.59),
        Cap("Vientiane", 17.97, 102.60), Cap("Beirut", 33.89, 35.50),
        Cap("Kuala Lumpur", 3.14, 101.69), Cap("Malé", 4.18, 73.51),
        Cap("Ulan Bator", 47.89, 106.91), Cap("Naypyidaw", 19.76, 96.08),
        Cap("Kathmandu", 27.72, 85.32), Cap("Phenian", 39.04, 125.76),
        Cap("Muscat", 23.59, 58.41), Cap("Islamabad", 33.69, 73.06),
        Cap("Manila", 14.60, 120.98), Cap("Doha", 25.29, 51.53),
        Cap("Riad", 24.71, 46.68), Cap("Singapore", 1.35, 103.82),
        Cap("Seul", 37.57, 126.98), Cap("Colombo", 6.93, 79.85),
        Cap("Damasc", 33.51, 36.29), Cap("Dușanbe", 38.56, 68.79),
        Cap("Bangkok", 13.76, 100.50), Cap("Dili", -8.56, 125.56),
        Cap("Așgabat", 37.96, 58.33), Cap("Abu Dhabi", 24.45, 54.38),
        Cap("Tașkent", 41.30, 69.24), Cap("Hanoi", 21.03, 105.85),
        Cap("Sanaa", 15.35, 44.21),

        // ── Africa ───────────────────────────────────────────────────────────
        Cap("Alger", 36.75, 3.06), Cap("Luanda", -8.84, 13.23),
        Cap("Porto-Novo", 6.50, 2.62), Cap("Gaborone", -24.65, 25.91),
        Cap("Ouagadougou", 12.37, -1.52), Cap("Gitega", -3.43, 29.93),
        Cap("Praia", 14.93, -23.51), Cap("Yaoundé", 3.85, 11.50),
        Cap("Bangui", 4.39, 18.56), Cap("N'Djamena", 12.13, 15.06),
        Cap("Moroni", -11.70, 43.26), Cap("Kinshasa", -4.32, 15.31),
        Cap("Brazzaville", -4.27, 15.27), Cap("Yamoussoukro", 6.83, -5.29),
        Cap("Djibouti", 11.59, 43.15), Cap("Cairo", 30.04, 31.24),
        Cap("Malabo", 3.75, 8.78), Cap("Asmara", 15.34, 38.93),
        Cap("Mbabane", -26.32, 31.13), Cap("Addis Abeba", 9.03, 38.74),
        Cap("Libreville", 0.42, 9.47), Cap("Banjul", 13.45, -16.58),
        Cap("Accra", 5.60, -0.19), Cap("Conakry", 9.64, -13.58),
        Cap("Bissau", 11.86, -15.60), Cap("Nairobi", -1.29, 36.82),
        Cap("Maseru", -29.31, 27.48), Cap("Monrovia", 6.30, -10.80),
        Cap("Tripoli", 32.89, 13.19), Cap("Antananarivo", -18.88, 47.51),
        Cap("Lilongwe", -13.96, 33.77), Cap("Bamako", 12.65, -8.00),
        Cap("Nouakchott", 18.08, -15.98), Cap("Port Louis", -20.16, 57.50),
        Cap("Rabat", 34.02, -6.83), Cap("Maputo", -25.97, 32.58),
        Cap("Windhoek", -22.56, 17.08), Cap("Niamey", 13.51, 2.11),
        Cap("Abuja", 9.06, 7.50), Cap("Kigali", -1.95, 30.06),
        Cap("São Tomé", 0.34, 6.73), Cap("Dakar", 14.69, -17.45),
        Cap("Victoria", -4.62, 55.45), Cap("Freetown", 8.48, -13.23),
        Cap("Mogadishu", 2.05, 45.32), Cap("Pretoria", -25.75, 28.19),
        Cap("Juba", 4.85, 31.58), Cap("Khartoum", 15.50, 32.56),
        Cap("Dodoma", -6.16, 35.75), Cap("Lomé", 6.13, 1.22),
        Cap("Tunis", 36.81, 10.18), Cap("Kampala", 0.35, 32.58),
        Cap("Lusaka", -15.42, 28.28), Cap("Harare", -17.83, 31.05),

        // ── Americile ────────────────────────────────────────────────────────
        Cap("Buenos Aires", -34.60, -58.38), Cap("Nassau", 25.06, -77.35),
        Cap("Bridgetown", 13.10, -59.62), Cap("Belmopan", 17.25, -88.77),
        Cap("Sucre", -19.04, -65.26), Cap("Brasília", -15.79, -47.88),
        Cap("Ottawa", 45.42, -75.70), Cap("Santiago", -33.45, -70.67),
        Cap("Bogotá", 4.71, -74.07), Cap("San José", 9.93, -84.08),
        Cap("Havana", 23.11, -82.37), Cap("Roseau", 15.30, -61.39),
        Cap("Santo Domingo", 18.49, -69.93), Cap("Quito", -0.18, -78.47),
        Cap("San Salvador", 13.69, -89.22), Cap("St. George's", 12.06, -61.75),
        Cap("Guatemala City", 14.63, -90.51), Cap("Georgetown", 6.80, -58.16),
        Cap("Port-au-Prince", 18.59, -72.31), Cap("Tegucigalpa", 14.07, -87.19),
        Cap("Kingston", 18.02, -76.80), Cap("Ciudad de México", 19.43, -99.13),
        Cap("Managua", 12.13, -86.25), Cap("Panama City", 8.98, -79.52),
        Cap("Asunción", -25.30, -57.64), Cap("Lima", -12.05, -77.04),
        Cap("Basseterre", 17.30, -62.72), Cap("Castries", 14.01, -60.99),
        Cap("Kingstown", 13.16, -61.23), Cap("Paramaribo", 5.87, -55.17),
        Cap("Washington", 38.90, -77.04), Cap("Montevideo", -34.90, -56.16),
        Cap("Caracas", 10.49, -66.88), Cap("St. John's", 17.12, -61.85),

        // ── Oceania ──────────────────────────────────────────────────────────
        Cap("Canberra", -35.28, 149.13), Cap("Suva", -18.14, 178.44),
        Cap("Tarawa", 1.45, 172.98), Cap("Majuro", 7.10, 171.38),
        Cap("Palikir", 6.92, 158.16), Cap("Yaren", -0.55, 166.92),
        Cap("Wellington", -41.29, 174.78), Cap("Ngerulmud", 7.50, 134.62),
        Cap("Port Moresby", -9.44, 147.18), Cap("Apia", -13.83, -171.77),
        Cap("Honiara", -9.43, 159.96), Cap("Nuku'alofa", -21.14, -175.20),
        Cap("Funafuti", -8.52, 179.20), Cap("Port Vila", -17.73, 168.32),
    )
}
