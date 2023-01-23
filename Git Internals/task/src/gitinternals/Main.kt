package gitinternals

import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.InflaterInputStream


const val NULL_CHAR = '\u0000'
const val PARENT = "parent"
const val AUTHOR = "author"

enum class ObjectType {
    BLOB, COMMIT, TREE
}

enum class Command(val value: String) {
    LIST_BRANCHES("list-branches"),
    CAT_FILE("cat-file"),
    LOG("log"),
    COMMIT_TREE("commit-tree")
}

object InputStream {
    lateinit var iis: InflaterInputStream
}

data class Commit(
    val id: String,
    val treeHash: String,
    val author: Person,
    val committer: Person,
    val message: String,
    val parentsIds: List<String>
)

data class Person(val name: String, val email: String, val timestamp: ZonedDateTime, val isAuthor: Boolean) {
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    override fun toString(): String {
        val timestampType = if (isAuthor) "original" else "commit"
        return "$name $email $timestampType timestamp: ${timestamp.format(dateTimeFormatter)}"
    }
}

fun main() {
    println("Enter .git directory location:")
    val gitDirectory = readln()
    println("Enter command:")
    when (readln()) {
        Command.CAT_FILE.value -> catFile(gitDirectory)
        Command.LIST_BRANCHES.value -> listBranches(gitDirectory)
        Command.LOG.value -> log(gitDirectory)
        Command.COMMIT_TREE.value -> outputCommitTree(gitDirectory)
    }
}

fun outputCommitTree(directory: String) {
    println("Enter commit-hash:")
    val commitId = readln()
    val treeHash = readCommit(directory, commitId).treeHash
    outputTreeMembers(directory, treeHash)
}

fun outputTreeMembers(directory: String, treeHash: String, parentTree: String = "") {
    val gitObjLocation = "$directory\\objects\\${treeHash.substring(0, 2)}\\${treeHash.substring(2)}"
    val fis = FileInputStream(gitObjLocation)
    val iis = InflaterInputStream(fis)
    readNextWord(iis)
    readNextWord(iis, delimiter = NULL_CHAR)
    while (iis.available() == 1) {
        readNextWord(iis)
        val filename = readNextWord(iis, delimiter = NULL_CHAR)
        var hash = ""
        repeat(20) {
            hash += "%02x".format(iis.read())
        }
        val treeObjLocation = "$directory\\objects\\${hash.substring(0, 2)}\\${hash.substring(2)}"
        val fisInner = FileInputStream(treeObjLocation)
        val iisInner = InflaterInputStream(fisInner)
        val fileType = readNextWord(iisInner)
        if (fileType != ObjectType.TREE.toString().lowercase()) {
            println(parentTree + filename)
        } else outputTreeMembers(directory, hash, "$filename/")
    }
}

fun log(directory: String) {
    println("Enter branch name:")
    val branch = readln()
    var commitId = File("$directory/refs/heads/$branch").readText().trim()
    var nextCommit = readCommit(directory, commitId)
    printCommit(nextCommit)
    while (nextCommit.parentsIds.isNotEmpty()) {
        if (nextCommit.parentsIds.size == 2) {
            val next = readCommit(directory, nextCommit.parentsIds[0])
            val merged = readCommit(directory, nextCommit.parentsIds[1])
            printCommit(merged, isMerged = true)
            printCommit(next)
            nextCommit = next
        } else {
            commitId = nextCommit.parentsIds[0]
            nextCommit = readCommit(directory, commitId)
            printCommit(nextCommit)
        }
    }
}

fun printCommit(commit: Commit, isMerged: Boolean = false) =
    println("Commit: ${commit.id}${if (isMerged) " (merged)" else ""}\n${commit.committer}\n${commit.message}\n")

fun listBranches(directory: String) {
    val headFile = File("$directory/HEAD")
    val curBranch = headFile.readText().trim().split("/").last()
    val dir = File("$directory/refs/heads")
    for (branch in dir.list()!!) {
        println(if (branch == curBranch) "* $branch" else "  $branch")
    }
}

fun catFile(directory: String) {
    println("Enter git object hash:")
    val objHash = readln()
    val gitObjLocation = "$directory\\objects\\${objHash.substring(0, 2)}\\${objHash.substring(2)}"
    val fis = FileInputStream(gitObjLocation)
    val iis = InflaterInputStream(fis)
    InputStream.iis = iis
    val objectType = ObjectType.valueOf(readNextWord().uppercase())
    readNextWord(delimiter = NULL_CHAR)
    when (objectType) {
        ObjectType.COMMIT -> printCommit()
        ObjectType.BLOB -> printBlob()
        ObjectType.TREE -> printTree()
    }
}

fun printTree() {
    println("*${ObjectType.TREE}*")
    while (InputStream.iis.available() == 1) {
        val permMetadataNum = readNextWord()
        val filename = readNextWord(delimiter = NULL_CHAR)
        var hash = ""
        repeat(20) {
            hash += "%02x".format(InputStream.iis.read())
        }
        println("$permMetadataNum $hash $filename")
    }
}

fun printBlob() {
    println("*${ObjectType.BLOB}*")
    while (InputStream.iis.available() == 1) {
        print(InputStream.iis.read().toChar())
    }
}

fun readNextWord(iis: InflaterInputStream = InputStream.iis, delimiter: Char = ' '): String {
    var result = ""
    var nextByte = iis.read()
    while (nextByte.toChar() != delimiter) {
        result += nextByte.toChar()
        nextByte = iis.read()
    }
    return result
}

fun getAuthorOrCommitter(nextInfo: String): Person {
    val isAuthor = nextInfo.contains(AUTHOR)
    val name = readNextWord()
    val email = readNextWord().drop(1).dropLast(1)
    val timestamp = Instant.ofEpochSecond(readNextWord().toLong()).atZone(
        ZoneOffset.of(
            readNextWord(delimiter = '\n')
        )
    )
    return Person(name, email, timestamp, isAuthor)
}

fun printlnAuthorOrCommitter(nextInfo: String, isAuthor: Boolean = true) {
    val type = if (isAuthor) "original" else "commit"
    val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    println(
        "$nextInfo: ${readNextWord()} ${readNextWord().drop(1).dropLast(1)} $type timestamp: ${
            Instant.ofEpochSecond(
                readNextWord().toLong()
            ).atZone(
                ZoneOffset.of(
                    readNextWord(delimiter = '\n')
                )
            ).format(dateTimeFormatter)
        }"
    )
}

fun readCommit(directory: String, commitHash: String): Commit {
    val gitObjLocation = "$directory\\objects\\${commitHash.substring(0, 2)}\\${commitHash.substring(2)}"
    val fis = FileInputStream(gitObjLocation)
    val iis = InflaterInputStream(fis)
    InputStream.iis = iis
    readNextWord()
    readNextWord(delimiter = NULL_CHAR)
    readNextWord()
    val treeHash = readNextWord(delimiter = '\n')
    var nextInfo = readNextWord()
    val parents = mutableListOf<String>()
    if (nextInfo.contains(PARENT)) {
        while (nextInfo.contains(PARENT)) {
            nextInfo = readNextWord(delimiter = '\n')
            parents += nextInfo
            nextInfo = readNextWord()
        }
    }
    val person1 = getAuthorOrCommitter(nextInfo)
    nextInfo = readNextWord()
    val person2 = getAuthorOrCommitter(nextInfo)
    val commitLines = mutableListOf<String>()
    while (InputStream.iis.available() == 1) {
        val commitLine = readNextWord(delimiter = '\n')
        if (commitLine.isNotBlank()) commitLines += commitLine
    }
    return Commit(commitHash, treeHash, person1, person2, commitLines.joinToString("\n"), parents)
}

fun printCommit() {
    println("*${ObjectType.COMMIT}*")
    println(readNextWord() + ": " + readNextWord(delimiter = '\n'))
    var nextInfo = readNextWord()
    if (nextInfo.contains(PARENT)) {
        val parents = mutableListOf<String>()
        while (nextInfo.contains(PARENT)) {
            nextInfo = readNextWord(delimiter = '\n')
            parents += nextInfo
            nextInfo = readNextWord()
        }
        println("parents: ${parents.joinToString(" | ")}")
    }
    printlnAuthorOrCommitter(nextInfo)
    nextInfo = readNextWord()
    printlnAuthorOrCommitter(nextInfo, isAuthor = false)
    println("commit message:")
    while (InputStream.iis.available() == 1) {
        val commitLine = readNextWord(delimiter = '\n')
        if (commitLine.isNotBlank()) println(commitLine)
    }
}
