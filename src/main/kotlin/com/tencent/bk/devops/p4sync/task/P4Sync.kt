package com.tencent.bk.devops.p4sync.task

import com.perforce.p4java.client.IClient
import com.perforce.p4java.core.IChangelistSummary
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.impl.mapbased.rpc.RpcPropertyDefs.RPC_SOCKET_SO_TIMEOUT_NICK
import com.perforce.p4java.impl.mapbased.rpc.stream.helper.RpcSocketHelper
import com.perforce.p4java.option.client.ParallelSyncOptions
import com.perforce.p4java.server.PerforceCharsets
import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.common.Status
import com.tencent.bk.devops.atom.pojo.AtomResult
import com.tencent.bk.devops.atom.pojo.StringData
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import com.tencent.bk.devops.p4sync.task.api.DevopsApi
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_HEAD_CHANGE_CLIENT_ID
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_HEAD_CHANGE_COMMENT
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_HEAD_CHANGE_ID
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_HEAD_CHANGE_USER
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_LAST_CHANGE_ID
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_P4_CHARSET
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_PORT
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_STREAM
import com.tencent.bk.devops.p4sync.task.constants.BK_CI_P4_DEPOT_WORKSPACE_PATH
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_CONTAINER_ID
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_DEPOT_P4_CHARSET
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_DEPOT_PORT
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_DEPOT_STREAM
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_LOCAL_PATH
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_P4_CLIENT_NAME
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_P4_REPO_ID
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_P4_REPO_NAME
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_P4_REPO_PATH
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_TASKID
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_TICKET_ID
import com.tencent.bk.devops.p4sync.task.constants.BK_REPO_TYPE
import com.tencent.bk.devops.p4sync.task.constants.EMPTY
import com.tencent.bk.devops.p4sync.task.constants.P4_CHANGELIST_MAX_MOST_RECENT
import com.tencent.bk.devops.p4sync.task.constants.P4_CHARSET
import com.tencent.bk.devops.p4sync.task.constants.P4_CLIENT
import com.tencent.bk.devops.p4sync.task.constants.P4_CONFIG_FILE_NAME
import com.tencent.bk.devops.p4sync.task.constants.P4_PORT
import com.tencent.bk.devops.p4sync.task.constants.P4_USER
import com.tencent.bk.devops.p4sync.task.enum.RepositoryType
import com.tencent.bk.devops.p4sync.task.enum.ScmType
import com.tencent.bk.devops.p4sync.task.p4.MoreSyncOptions
import com.tencent.bk.devops.p4sync.task.p4.P4Client
import com.tencent.bk.devops.p4sync.task.pojo.CommitData
import com.tencent.bk.devops.p4sync.task.pojo.P4SyncParam
import com.tencent.bk.devops.p4sync.task.pojo.PipelineBuildMaterial
import com.tencent.bk.devops.p4sync.task.pojo.RepositoryConfig
import com.tencent.bk.devops.p4sync.task.service.AuthService
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Properties
import kotlin.random.Random

@AtomService(paramClass = P4SyncParam::class)
class P4Sync : TaskAtom<P4SyncParam> {

    private val logger = LoggerFactory.getLogger(P4Sync::class.java)

    override fun execute(context: AtomContext<P4SyncParam>) {
        val param = context.param
        val result = context.result
        checkParam(param, result)
        if (result.status != Status.success) {
            return
        }
        val credentialInfo = AuthService(param, result, DevopsApi()).getCredentialInfo()
        if (param.httpProxy != null && param.httpProxy.contains(':')) {
            val proxyParam = param.httpProxy.split(':')
            RpcSocketHelper.httpProxyHost = proxyParam[0]
            RpcSocketHelper.httpProxyPort = proxyParam[1].toInt()
        }
        val userName = credentialInfo[0]
        val credential = credentialInfo[1]
        val executeResult = syncWithTry(param, result, userName, credential)
        setOutPut(context, executeResult)
    }

    fun syncWithTry(param: P4SyncParam, result: AtomResult, userName: String, credential: String): ExecuteResult {
        var sync: ExecuteResult? = null
        try {
            sync = sync(param, userName, credential)
            if (!sync.result) {
                result.status = Status.failure
            }
        } catch (e: Exception) {
            result.status = Status.failure
            result.message = e.message
            logger.error("Synchronization failure", e)
        }
        return sync ?: ExecuteResult()
    }

    private fun sync(param: P4SyncParam, userName: String, credential: String): ExecuteResult {
        with(param) {
            val useSSL = param.p4port.startsWith("ssl:")
            val p4client = P4Client(
                uri = if (useSSL) "p4javassl://${param.p4port.substring(4)}" else "p4java://${param.p4port}",
                userName = userName,
                password = credential,
                charsetName,
                getProperties(this)
            )
            p4client.use {
                return execute(param, p4client)
            }
        }
    }

    private fun execute(
        param: P4SyncParam,
        p4client: P4Client
    ): ExecuteResult {
        with(param) {
            val result = ExecuteResult()
            val client = param.getClient(p4client)
            try {
                result.depotUrl = p4client.uri
                result.stream = stream ?: EMPTY
                result.charset = charsetName
                result.workspacePath = client.root
                result.clientName = client.name
                val fileSpecs = FileSpecBuilder.makeFileSpecList(getFileSpecList())
                saveChanges(p4client, client, result, param, fileSpecs)
                // 保存client信息
                save(client, p4port, charsetName)
                if (autoCleanup) {
                    p4client.cleanup(client)
                }
                val syncOptions = MoreSyncOptions(
                    forceUpdate, noUpdate, clientBypass,
                    serverBypass, quiet, safetyCheck, max
                )
                val parallelSyncOptions = ParallelSyncOptions(
                    batch, batchSize, minimum,
                    minimumSize, numberOfThreads, null
                )
                val ret = p4client.sync(client, syncOptions, parallelSyncOptions, fileSpecs, keepGoingOnError)
                result.result = ret
                // unshelve
                unshelveId?.let {
                    logger.info("unshelve id $unshelveId.")
                    p4client.unshelve(unshelveId, client)
                }
                return result
            } finally {
                clientName ?: let {
                    // 删除临时client
                    p4client.deleteClient(client.name)
                }
            }
        }
    }

    private fun setOutPut(context: AtomContext<P4SyncParam>, executeResult: ExecuteResult) {
        context.result.data[BK_CI_P4_DEPOT_HEAD_CHANGE_ID] = StringData(executeResult.headCommitId)
        context.result.data[BK_CI_P4_DEPOT_HEAD_CHANGE_COMMENT] = StringData(executeResult.headCommitComment)
        context.result.data[BK_CI_P4_DEPOT_HEAD_CHANGE_CLIENT_ID] = StringData(executeResult.headCommitClientId)
        context.result.data[BK_CI_P4_DEPOT_HEAD_CHANGE_USER] = StringData(executeResult.headCommitUser)
        context.result.data[BK_CI_P4_DEPOT_LAST_CHANGE_ID] = StringData(executeResult.lastCommitId)
        context.result.data[BK_CI_P4_DEPOT_WORKSPACE_PATH] = StringData(executeResult.workspacePath)
        context.result.data[BK_CI_P4_DEPOT_PORT] = StringData(executeResult.depotUrl)
        context.result.data[BK_CI_P4_DEPOT_STREAM] = StringData(executeResult.stream)
        context.result.data[BK_CI_P4_DEPOT_P4_CHARSET] = StringData(executeResult.charset)
        // 设置CodeCC扫描需要的仓库信息
        setOutPutForCodeCC(context, executeResult)
    }

    private fun setOutPutForCodeCC(context: AtomContext<P4SyncParam>, executeResult: ExecuteResult) {
        val taskId = context.param.pipelineTaskId
        context.result.data[BK_REPO_TASKID + taskId] = StringData(context.param.pipelineTaskId)
        context.result.data[BK_REPO_CONTAINER_ID + taskId] =
            StringData(context.allParameters["pipeline.job.id"]?.toString() ?: "")
        context.result.data[BK_REPO_TYPE + taskId] = StringData("perforce")
        context.result.data[BK_REPO_TICKET_ID + taskId] = StringData(context.param.ticketId)
        context.result.data[BK_REPO_DEPOT_PORT + taskId] = StringData(executeResult.depotUrl)
        context.result.data[BK_REPO_DEPOT_STREAM + taskId] = StringData(executeResult.stream)
        context.result.data[BK_REPO_DEPOT_P4_CHARSET + taskId] = StringData(executeResult.charset)
        context.result.data[BK_REPO_P4_CLIENT_NAME + taskId] = StringData(executeResult.clientName)
        context.result.data[BK_REPO_LOCAL_PATH + taskId] = StringData(context.param.rootPath ?: "")
    }

    private fun checkParam(param: P4SyncParam, result: AtomResult) {
        with(param) {
            // 检查输出路径
            try {
                rootPath?.let { checkPathWriteAbility(rootPath) }
            } catch (e: Exception) {
                result.status = Status.failure
                result.message = "The output path of the synchronized file is unavailable: ${e.message}"
            }
            // 检查字符集
            if (!PerforceCharsets.isSupported(charsetName)) {
                result.status = Status.failure
                result.message = "Charset $charsetName not supported."
            }
            // 检查代码库参数
            checkRepositoryInfo(param, result)
        }
    }

    private fun checkPathWriteAbility(path: String) {
        Files.createDirectories(Paths.get(path))
        val tmpFile = Paths.get(path, "check_${System.currentTimeMillis()}")
        val output = Files.newOutputStream(tmpFile)
        output.use { out ->
            Random.nextBytes(1024).inputStream().use {
                it.copyTo(out)
            }
        }
        Files.delete(tmpFile)
    }

    private fun save(client: IClient, uri: String, charsetName: String) {
        val p4user = client.server.userName
        val configFilePath = getP4ConfigPath(client)
        val outputStream = Files.newOutputStream(configFilePath)
        val printWriter = PrintWriter(outputStream)
        printWriter.use {
            printWriter.println("$P4_USER=$p4user")
            printWriter.println("$P4_PORT=$uri")
            printWriter.println("$P4_CLIENT=${client.name}")
            if (client.server.supportsUnicode()) {
                printWriter.println("$P4_CHARSET=$charsetName")
            }
            logger.info("Save p4 config to [${configFilePath.toFile().canonicalPath}] success.")
        }
    }

    private fun saveChanges(
        p4Client: P4Client,
        client: IClient,
        result: ExecuteResult,
        param: P4SyncParam,
        fileSpecs: List<IFileSpec>
    ) {
        var changeList = if (client.stream != null) {
            p4Client.getChangeListByStream(
                max = P4_CHANGELIST_MAX_MOST_RECENT,
                streamName = client.stream,
                fileSpecs = fileSpecs
            )
        } else {
            p4Client.getChangeList(
                max = P4_CHANGELIST_MAX_MOST_RECENT,
                fileSpecs = fileSpecs
            )
        }
        // 最新修改
        changeList.firstOrNull()?.let {
            val logChange = formatChange(it)
            result.headCommitId = it.id.toString()
            result.headCommitComment = it.description
            result.headCommitClientId = it.clientId
            result.headCommitUser = it.username
            logger.info(logChange)
        }
        // 指定同步文件版本后需对修改记录进行排序/去重，新纪录前面
        changeList = changeList.distinctBy { it.id }.sortedBy { -it.id }
        // 对比历史构建，提取本次构建拉取的commit
        changeList = getDiffChangeLists(changeList, param)
        if (changeList.isNotEmpty()) {
            // 保存原材料
            saveBuildMaterial(changeList, param)
            // 保存提交信息
            saveChangeCommit(changeList, param)
        } else {
            logger.info("Already up to date,Do not save commit")
        }
    }

    private fun formatChange(change: IChangelistSummary): String {
        val format = SimpleDateFormat("yyyy/MM/dd")
        val date = change.date
        val desc = change.description.dropLast(1)
        return "Change ${change.id} on ${format.format(date)} by ${change.username}@${change.clientId} '$desc '"
    }

    private fun getP4ConfigPath(client: IClient): Path {
        return Paths.get(client.root, P4_CONFIG_FILE_NAME)
    }

    private fun getProperties(param: P4SyncParam): Properties {
        val properties = Properties()
        properties.setProperty(RPC_SOCKET_SO_TIMEOUT_NICK, param.netMaxWait.toString())
        return properties
    }

    private fun checkRepositoryInfo(param: P4SyncParam, result: AtomResult) {
        // 代码库类型若为空则进行默认值处理
        param.repositoryType = param.repositoryType ?: RepositoryType.URL.name
        with(param) {
            when (RepositoryType.valueOf(repositoryType!!)) {
                RepositoryType.ID -> {
                    repositoryHashId ?: run {
                        result.status = Status.failure
                        result.message = "The repository hashId cannot be empty"
                    }
                    result.data[BK_REPO_P4_REPO_ID] = StringData(repositoryHashId)
                }

                RepositoryType.NAME -> {
                    repositoryName ?: run {
                        result.status = Status.failure
                        result.message = "The repository name cannot be empty"
                    }
                    result.data[BK_REPO_P4_REPO_NAME] = StringData(repositoryName)
                }

                RepositoryType.URL -> {
                    result.data[BK_REPO_P4_REPO_PATH] = StringData(p4port)
                    null
                }
            }
        }
    }

    private fun saveBuildMaterial(changeList: List<IChangelistSummary>, param: P4SyncParam) {
        changeList.first().let {
            if (param.repositoryName.isNullOrBlank()) {
                param.repositoryName = param.p4port
            }
            DevopsApi().saveBuildMaterial(
                mutableListOf(
                    PipelineBuildMaterial(
                        aliasName = param.repositoryName,
                        url = param.p4port,
                        branchName = param.stream,
                        newCommitId = "${it.id}",
                        newCommitComment = it.description,
                        commitTimes = changeList.size,
                        scmType = ScmType.CODE_P4.name
                    )
                )
            )
        }
    }

    private fun saveChangeCommit(changeList: List<IChangelistSummary>, param: P4SyncParam) {
        with(param) {
            val commitData = changeList.map {
                CommitData(
                    type = ScmType.parse(ScmType.CODE_P4),
                    pipelineId = pipelineId,
                    buildId = pipelineBuildId,
                    commit = "${it.id}",
                    committer = it.username,
                    author = it.username,
                    commitTime = it.date.time / 1000, // 单位:秒
                    comment = it.description.trim(),
                    repoId = repositoryHashId,
                    repoName = repositoryName,
                    elementId = pipelineTaskId,
                    url = p4port
                )
            }
            DevopsApi().addCommit(commitData)
        }
    }

    private fun getDiffChangeLists(
        sourceChangeList: List<IChangelistSummary>,
        param: P4SyncParam
    ): List<IChangelistSummary> {
        if (sourceChangeList.isEmpty()) {
            return sourceChangeList
        }
        val result = mutableListOf<IChangelistSummary>()
        with(param) {
            var repositoryConfig: RepositoryConfig? = null
            if (repositoryType != RepositoryType.URL.name) {
                repositoryConfig = RepositoryConfig(
                    repositoryHashId = repositoryHashId,
                    repositoryName = repositoryName,
                    repositoryType = RepositoryType.valueOf(repositoryType!!)
                )
            }
            // 获取历史信息
            val latestCommit = DevopsApi().getLatestCommit(
                pipelineId = pipelineId,
                elementId = pipelineTaskId,
                repoId = repositoryConfig?.getURLEncodeRepositoryId() ?: "",
                repositoryType = repositoryConfig?.repositoryType?.name ?: ""
            )
            if (latestCommit.data.isNullOrEmpty()) {
                return sourceChangeList
            }
            val first = latestCommit.data?.first() ?: return sourceChangeList
            sourceChangeList.forEach {
                if (it.id > first.commit.toInt()) {
                    result.add(it)
                }
            }
            return result
        }
    }
}
