package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary

/** How projects are ordered in the grouped thread list. */
enum class ProjectOrder { Activity, Alphabetical, Server }

/** How threads are ordered inside one project. */
enum class ThreadOrder { Activity, Created }

/** Stable key of the bucket for threads whose project is not reported by the environment. */
internal const val NoProjectKey = "no-project"

/**
 * One project's row and the threads listed under it. [project] is null for [NoProjectKey]: threads whose project was
 * removed from the environment, or never reported. [needsYou] and [working] are the counts the header shows.
 */
internal data class ProjectGroup(
    val key: String,
    val project: Project?,
    val threads: List<ThreadSummary>,
    val needsYou: Int,
    val working: Int,
) {
    val title: String get() = project?.title?.takeIf { it.isNotBlank() } ?: NoProjectTitle
}

/** Group heading for threads whose project is unknown. */
internal const val NoProjectTitle = "Без проекта"

/**
 * Threads grouped by project, for the list's top level. Empty projects are dropped: a heading with no rows is noise.
 *
 * Order: pinned projects first, each band ordered by its own rule ([pinnedOrder], [otherOrder]). The orphan bucket
 * always sorts last inside its band, so threads without a project never displace a real one.
 */
internal fun projectGroups(
    threads: List<ThreadSummary>,
    projects: List<Project>,
    pinned: Set<String> = emptySet(),
    pinnedOrder: ProjectOrder = ProjectOrder.Alphabetical,
    otherOrder: ProjectOrder = ProjectOrder.Activity,
    threadOrder: ThreadOrder = ThreadOrder.Activity,
    now: Long = System.currentTimeMillis(),
): List<ProjectGroup> {
    // Archived threads keep their own folded row: they are put away, not part of any project's working set.
    val rows = threadListRows(threads).filter { !it.archived && it.archivedAt == null }
    val byProject = rows.groupBy { it.projectId }
    val known = projects.associateBy { it.id }
    // A project's position must agree with the anchor its threads sort by, so both come from threadActivityAnchor.
    val activity = rows.groupingBy { it.projectId }
        .fold(Long.MIN_VALUE) { latest, row -> maxOf(latest, threadActivityAnchor(row)) }

    fun group(key: String, project: Project?): ProjectGroup? {
        val members = byProject[key] ?: return null
        if (members.isEmpty()) return null
        val sorted = when (threadOrder) {
            ThreadOrder.Created -> members.sortedWith(
                compareByDescending<ThreadSummary> { timestamp(it.createdAt) }.thenBy { it.id },
            )
            ThreadOrder.Activity -> members.sortedWith(
                compareByDescending<ThreadSummary> { threadActivityAnchor(it) }.thenBy { it.id },
            )
        }
        return ProjectGroup(
            key = key,
            project = project,
            threads = sorted,
            needsYou = sorted.count(::threadNeedsYou),
            working = sorted.count(::threadIsWorking),
        )
    }

    val orphans = group(NoProjectKey, null)

    // Threads whose project the environment no longer reports fall into the orphan bucket rather than disappearing.
    val missing = byProject.keys.filter { it !in known && it != NoProjectKey }
    val orphansWithMissing = if (missing.isEmpty()) orphans else {
        val combined = orphans?.threads.orEmpty() + missing.flatMap { byProject[it].orEmpty() }
        val sorted = when (threadOrder) {
            ThreadOrder.Created -> combined.sortedWith(compareByDescending<ThreadSummary> { timestamp(it.createdAt) }.thenBy { it.id })
            ThreadOrder.Activity -> combined.sortedWith(compareByDescending<ThreadSummary> { threadActivityAnchor(it) }.thenBy { it.id })
        }
        ProjectGroup(NoProjectKey, null, sorted, sorted.count(::threadNeedsYou), sorted.count(::threadIsWorking))
    }

    fun band(order: ProjectOrder, members: List<Project>): List<Project> = when (order) {
        ProjectOrder.Alphabetical -> members.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        ProjectOrder.Server -> members
        ProjectOrder.Activity -> members.sortedWith(
            compareByDescending<Project> { activity[it.id] ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

    val (pinnedProjects, otherProjects) = projects.partition { it.id in pinned }
    val ordered = band(pinnedOrder, pinnedProjects) + band(otherOrder, otherProjects)
    return ordered.mapNotNull { group(it.id, it) } + listOfNotNull(orphansWithMissing)
}

/**
 * The timestamp a thread sorts by inside a project under [ThreadOrder.Activity]: the same anchors the inbox shelf uses,
 * so a thread that just woke or finished surfaces above older ones.
 */
internal fun threadActivityAnchor(thread: ThreadSummary): Long = maxOf(
    timestamp(thread.createdAt),
    timestamp(thread.unsettledAt),
    timestamp(thread.latestRunRequestedAt),
    timestamp(thread.latestRunCompletedAt),
    timestamp(thread.latestUserAuthoredMessageAt),
)

/** Threads of a group that pass the chip filter. Needs-you and working are mutually exclusive slices. */
internal fun ProjectGroup.visibleThreads(filter: ThreadFilter): List<ThreadSummary> = when (filter) {
    ThreadFilter.All -> threads
    ThreadFilter.NeedsYou -> threads.filter(::threadNeedsYou)
    ThreadFilter.Working -> threads.filter(::threadIsWorking)
}

/** Count shown on the chips, over the already-grouped rows. */
internal fun projectGroupsFilterCounts(groups: List<ProjectGroup>): Map<ThreadFilter, Int> = mapOf(
    ThreadFilter.All to groups.sumOf { it.threads.size },
    ThreadFilter.NeedsYou to groups.sumOf { it.needsYou },
    ThreadFilter.Working to groups.sumOf { it.working },
)
