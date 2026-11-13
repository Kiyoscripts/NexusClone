package com.kiyoscripts.nexusclone.util

import com.kiyoscripts.nexusclone.data.AppsRepository
import com.kiyoscripts.nexusclone.data.FakeLocationRepository
import com.kiyoscripts.nexusclone.data.GmsRepository
import com.kiyoscripts.nexusclone.data.XpRepository
import com.kiyoscripts.nexusclone.view.apps.AppsFactory
import com.kiyoscripts.nexusclone.view.fake.FakeLocationFactory
import com.kiyoscripts.nexusclone.view.gms.GmsFactory
import com.kiyoscripts.nexusclone.view.list.ListFactory
import com.kiyoscripts.nexusclone.view.xp.XpFactory

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 22:38
 */
object InjectionUtil {

    private val appsRepository = AppsRepository()

    private val xpRepository = XpRepository()

    private val gmsRepository = GmsRepository()

    private val fakeLocationRepository = FakeLocationRepository()

    fun getAppsFactory() : AppsFactory {
        return AppsFactory(appsRepository)
    }

    fun getListFactory(): ListFactory {
        return ListFactory(appsRepository)
    }

    fun getXpFactory():XpFactory{
        return XpFactory(xpRepository)
    }

    fun getGmsFactory():GmsFactory{
        return GmsFactory(gmsRepository)
    }

    fun getFakeLocationFactory():FakeLocationFactory{
        return FakeLocationFactory(fakeLocationRepository)
    }
}