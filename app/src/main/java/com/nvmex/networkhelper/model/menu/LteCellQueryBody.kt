package com.nvmex.networkhelper.model.menu


data class LteCellQueryBody(
    val tac: Int,
    val earfcn: Int,
    val eci: Long? = null,
    val pci: Int? = null,
    val cell_id: Int? = null
)