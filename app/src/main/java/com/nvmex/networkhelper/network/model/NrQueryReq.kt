package com.nvmex.networkhelper.network.model

import com.google.gson.annotations.SerializedName

data class NrQueryReq(
    @SerializedName("gcell_id")
    val gcellId: String? = null,

    @SerializedName("nr_tac")
    val nrTac: Int? = null,

    @SerializedName("nr_arfcn")
    val nrArfcn: Int? = null,

    @SerializedName("nr_pci")
    val nrPci: Int? = null
)