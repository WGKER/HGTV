package com.hongguotv.core

fun main(args: Array<String>) {
    val repository=ContentRepository()
    // 搜索接口要求登录态（匿名设备返回 200 + 空 body），联调时可用 --token <x-tt-token> 带上。
    val tokenAt=args.indexOf("--token")
    if(tokenAt>=0) repository.sessionToken=args.getOrNull(tokenAt+1)
    fun keywordArg(): String? = args.withIndex().firstOrNull { (index,value) -> !value.startsWith("--") && value.isNotBlank() && index!=tokenAt+1 }?.value
    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--search")) {
        // 搜索联调：默认「聚宝」（漫剧），也可以给关键词与 --short。
        try {
            val type=if(args.contains("--short")) ContentType.SHORT else ContentType.COMIC
            val keyword=keywordArg() ?: "聚宝"
            val seen=mutableSetOf<String>()
            for(page in 1..6) {
                val result=try { repository.search(keyword,page,type) } catch(problem: Exception) {
                    println("search=$keyword type=${type.label} page=$page failed=${problem.message}")
                    if(repository.sessionToken.isNullOrBlank()) println("   提示：App 搜索接口匿名不可用时已退回官网搜索；两条路都失败请检查网络，或用 --token <x-tt-token> 让搜索直接使用 App 接口。")
                    break
                }
                val fresh=result.items.filter { seen.add(it.id) }
                println("search=$keyword type=${type.label} page=$page items=${result.items.size} new=${fresh.size} hasMore=${result.hasMore}")
                fresh.take(3).forEach { println("   ${it.id} ${it.title}") }
                if(!result.hasMore) break
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--danmaku")) {
        // 弹幕接口联调：默认取漫剧热播榜第一部，也可以直接给剧集 ID。
        try {
            val seriesId=args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: repository.comicRanking().items.first().id
            val detail=repository.detail(seriesId)
            val episode=detail.episodes.first()
            val items=repository.danmaku(episode,detail.series.id)
            println("danmaku series=${detail.series.title}; episodes=${detail.episodes.size}; vid=$episode; count=${items.size}")
            println("span=${items.firstOrNull()?.offsetMs}ms..${items.lastOrNull()?.offsetMs}ms; top=${items.maxByOrNull { it.diggCount }?.diggCount}赞")
            items.take(3).forEach { println("  ${it.offsetMs}ms [${it.diggCount}] ${it.text}") }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    // 搜索优先走 App 搜索接口，匿名设备下退回官网搜索；两条路都失败也不打断后面的首页 / 详情 / 播放探测。
    val search=try { repository.search(keyword,type=type) } catch(problem: Exception) {
        println("search failed=${problem.message}")
        if(repository.sessionToken.isNullOrBlank()) println("   提示：App 搜索接口匿名不可用时已退回官网搜索；两条路都失败请检查网络，或用 --token <x-tt-token> 让搜索直接使用 App 接口。")
        null
    }
    if(search!=null) {
        println("search=${search.items.size}; hasMore=${search.hasMore}")
        if(type==ContentType.COMIC && search.hasMore) {
            val next=repository.search(keyword,2,type)
            println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
        }
    }
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if(type==ContentType.COMIC) {
        val next=repository.home(2,type)
        println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
