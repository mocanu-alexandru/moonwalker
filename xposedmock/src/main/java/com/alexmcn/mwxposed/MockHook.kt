package com.alexmcn.mwxposed

import android.os.Parcel
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Modul LSPosed care face ca locațiile mock să apară ca reale (source=Phone) pentru Bump.
 *
 * Bump (nucleu Rust) respinge pozițiile cu flagul de mock ("invalid position due to MockPosition").
 * Flagul NU e citit prin Location.isMock() (deci hook-ul pe getter e inutil) — e citit nativ din
 * câmpul obiectului. Soluția validată: ștergem bitul de mock pe FIECARE Location chiar când e
 * construit din parcel (createFromParcel = chokepoint prin care trece orice locație livrată
 * cross-process de la FLP/GMS către Bump), înainte ca nucleul nativ să-l citească.
 */
class MockHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "co.amo.android.location") return
        try {
            val locationCls = XposedHelpers.findClass("android.location.Location", lpparam.classLoader)
            val creator = XposedHelpers.getStaticObjectField(locationCls, "CREATOR")
            XposedHelpers.findAndHookMethod(
                creator.javaClass, "createFromParcel", Parcel::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        clearMock(param.result ?: return)
                    }
                }
            )
            // Belt-and-suspenders: dacă vreodată Bump ar citi prin getter, întoarce false.
            XposedHelpers.findAndHookMethod(locationCls, "isMock", XC_MethodReplacement.returnConstant(false))
            runCatching {
                XposedHelpers.findAndHookMethod(locationCls, "isFromMockProvider", XC_MethodReplacement.returnConstant(false))
            }
            XposedBridge.log("MWMock: hooks installed for Bump")
        } catch (t: Throwable) {
            XposedBridge.log("MWMock: error $t")
        }
    }

    private fun clearMock(loc: Any) {
        // calea curată: setMock(false) (API 31+, ascuns dar accesibil din LSPosed)
        runCatching { XposedHelpers.callMethod(loc, "setMock", false); return }
        // fallback: șterge bitul de mock din mFieldsMask prin reflecție
        runCatching {
            val f = loc.javaClass.getDeclaredField("mFieldsMask").apply { isAccessible = true }
            f.setInt(loc, f.getInt(loc) and 0x40.inv() and 0x80.inv())
        }
    }
}
