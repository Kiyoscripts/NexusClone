package com.kiyoscripts.nexusclone.util

import androidx.annotation.StringRes
import com.kiyoscripts.nexusclone.app.App


fun getString(@StringRes id:Int,vararg arg:String):String{
    if(arg.isEmpty()){
        return App.getContext().getString(id)
    }
    return App.getContext().getString(id,*arg)
}

