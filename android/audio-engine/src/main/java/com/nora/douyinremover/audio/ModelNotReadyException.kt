package com.nora.douyinremover.audio

/** 模型文件未就绪（APK 已不含模型，需先通过 ModelDownloader 下载） */
class ModelNotReadyException(val modelFileName: String) :
    Exception("模型 $modelFileName 未下载，请先联网获取模型资源")
