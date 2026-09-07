package dev.artzig.window

import android.os.Binder
import android.os.Parcel
import android.window.InputTransferToken

object StockInputTransferSmoke {
    fun run() {
        val binder = Binder()
        val token = InputTransferToken(binder)
        val same = InputTransferToken(binder)
        check(token.token === binder && token == same && token.hashCode() == same.hashCode())
        check(token != InputTransferToken())
        check(token.describeContents() == 0)
        val parcel = Parcel.obtain()
        try {
            token.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val roundTrip = InputTransferToken.CREATOR.createFromParcel(parcel)
            check(roundTrip == token && roundTrip.hashCode() == token.hashCode())
            check(roundTrip.token === binder && parcel.dataAvail() == 0)
        } finally { parcel.recycle() }
        val native = InputTransferToken()
        check(InputTransferToken(native.token) == native)
    }
}
