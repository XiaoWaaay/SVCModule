package moe.fuqiuluo.mamu.driver

interface SearchProgressCallback {
    /**
     * Search全部Completed
     * @param totalFound 总共找To的结果数
     * @param totalRegions 总区域数
     * @param elapsedMillis 总耗时（毫秒）
     */
    fun onSearchComplete(totalFound: Long, totalRegions: Int, elapsedMillis: Long)
}
