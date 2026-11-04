/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * Reading the neighbour table over netlink is csicar/Ning's third ARP source
 * (LGPL-3.0-or-later), app/src/main/java/de/csicar/ning/scanner/ArpScanner.kt. Ning
 * gets it from the ip-neigh-sdk30 artifact; the dump below is written against the
 * kernel's rtnetlink ABI.
 *
 * See COPYING.txt for more details.
 */

// Reader for the kernel's neighbour table, asked over netlink.
//
// com.limelight.util.NeighborNetlink loads this as libnetlink-neigh.so and calls
// dumpNeighbors() once per scan round. It exists because the two ways an app has
// traditionally read that table both disappear on a modern Android build: /proc/net/arp
// is hidden from unprivileged processes, and executing the `ip` binary is denied by
// SELinux on many ROMs. Neither of those is a property of the kernel - the information
// is still there, and the same route `ip neigh` itself uses, an RTM_GETNEIGH dump on a
// NETLINK_ROUTE socket, is a plain syscall that no app-facing restriction covers.
//
// The reply is turned into one "192.168.1.1 aa:bb:cc:dd:ee:ff" line per entry. Nothing
// here is taken on trust by the caller: LanScanner re-checks the shape of both halves
// before a line can become a card, so a kernel that starts reporting something else
// simply stops contributing entries rather than poisoning the table.

#include <jni.h>
#include <android/log.h>

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/neighbour.h>

#define LOG_TAG "NetlinkNeigh"

/**
 * The log switch, owned on the Java side by AppLogging and pushed here through
 * Java_com_limelight_util_NeighborNetlink_setLogEnabled the moment the library loads.
 * "Enable log recording" off means the app produces no log lines at all, and a scan
 * that logs one INFO line per dump would contradict that on every search.
 */
static volatile int gLogEnabled = 1;

#define nl_log(priority, tag, ...) \
    do { if (gLogEnabled) __android_log_print(priority, tag, __VA_ARGS__); } while (0)

/**
 * Ceiling on what one dump may report. A /24 has 254 addresses, so a table far larger
 * than this is a router's, not a phone's; stopping there keeps a mistake from turning
 * into an unbounded allocation on the scan thread.
 */
#define MAX_NEIGHBORS 2048

/**
 * Netlink messages are datagrams, so one recv() has to hold a whole one. The kernel
 * respects the buffer size and never splits an RTM_NEWNEIGH across reads, so the only
 * cost of asking for 64K is the allocation itself.
 */
#define RECV_BUF_SIZE 65536

/**
 * Upper bound on waiting for the kernel's answer, per recv. A dump that stalls here means
 * something is wrong with the socket rather than with the network, and a scan round must
 * not be held up by it: whatever arrived before the deadline is still reported.
 */
#define DUMP_TIMEOUT_MS 3000

typedef struct {
    char ip[INET_ADDRSTRLEN];
    char mac[18];                    // "aa:bb:cc:dd:ee:ff" plus the terminator
} Neighbor;

/**
 * Record one RTM_NEWNEIGH message, if it describes something this app can act on.
 *
 * Three things have to hold, and each rules out a case that would otherwise be handed to
 * Wake on LAN as if it were a machine: the address must be IPv4 (the walk, the profiles
 * and the neighbour file are IPv4 only, so an IPv6 line would be a key nothing else can
 * produce), a link address must actually be present, and the entry must not be one the
 * kernel itself calls unresolved. `NUD_FAILED` neighbours are dumped with no NDA_LLADDR
 * at all on current kernels, so the state test is belt to the braces of the length test.
 */
static void collect(struct nlmsghdr *header, Neighbor *list, int *count) {
    if (*count >= MAX_NEIGHBORS) {
        return;
    }

    struct ndmsg *message = (struct ndmsg *) NLMSG_DATA(header);
    if (message->ndm_family != AF_INET) {
        return;
    }
    if (message->ndm_state & (NUD_INCOMPLETE | NUD_FAILED)) {
        return;
    }

    struct rtattr *attr = (struct rtattr *)
            ((char *) message + NLMSG_ALIGN(sizeof(struct ndmsg)));
    int remaining = NLMSG_PAYLOAD(header, sizeof(struct ndmsg));

    void *dst = NULL;
    void *link_address = NULL;
    while (RTA_OK(attr, remaining)) {
        if (attr->rta_type == NDA_DST && RTA_PAYLOAD(attr) == sizeof(struct in_addr)) {
            dst = RTA_DATA(attr);
        } else if (attr->rta_type == NDA_LLADDR && RTA_PAYLOAD(attr) == 6) {
            // Six bytes is an Ethernet address. Anything longer (InfiniBand, CAN) has no
            // place in this app, and the WoL magic packet is defined on Ethernet only.
            link_address = RTA_DATA(attr);
        }
        attr = RTA_NEXT(attr, remaining);
    }

    if (dst == NULL || link_address == NULL) {
        return;
    }

    Neighbor *entry = &list[(*count)++];
    if (inet_ntop(AF_INET, dst, entry->ip, sizeof(entry->ip)) == NULL) {
        *count -= 1;
        return;
    }
    const unsigned char *raw = (const unsigned char *) link_address;
    snprintf(entry->mac, sizeof(entry->mac), "%02x:%02x:%02x:%02x:%02x:%02x",
             raw[0], raw[1], raw[2], raw[3], raw[4], raw[5]);
}

/**
 * Ask for the table and collect the answer into [list]; returns how many entries were
 * recorded, or -1 when the question could not be asked at all.
 *
 * Every step is logged with its errno. "The netlink source gave nothing" is otherwise
 * indistinguishable from "the network has no neighbours", and the first means this route
 * is closed on this device while the second means it is working.
 */
static int dump(Neighbor *list) {
    int count = 0;

    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) {
        nl_log(ANDROID_LOG_ERROR, LOG_TAG,
                            "Cannot create a NETLINK_ROUTE socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_nl local;
    memset(&local, 0, sizeof(local));
    local.nl_family = AF_NETLINK;
    // Pid 0 asks the kernel to pick one.
    local.nl_pid = 0;
    local.nl_groups = 0;
    // Binding is optional for netlink: the sendto() below implicitly binds the socket
    // with a kernel-assigned portid, and the dump replies route back to it. Huawei's
    // SELinux denies an unprivileged app an explicit bind() on netlink_route_socket
    // (EACCES) while still allowing create/send/recv - which is why this dump used to
    // come back empty here even though the same route works. So a bind failure is
    // expected and must NOT abort the dump; try it only to claim a fixed portid, then
    // carry on regardless.
    if (bind(fd, (struct sockaddr *) &local, sizeof(local)) < 0) {
        nl_log(ANDROID_LOG_INFO, LOG_TAG,
                            "bind unavailable (%s); relying on implicit bind at sendto", strerror(errno));
    }

    struct {
        struct nlmsghdr header;
        struct ndmsg message;
    } request;
    memset(&request, 0, sizeof(request));
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(struct ndmsg));
    request.header.nlmsg_type = RTM_GETNEIGH;
    request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    request.header.nlmsg_seq = 1;
    request.message.ndm_family = AF_INET;
    // Ask for the resolved states explicitly rather than leaving the mask zero. On kernels
    // that read a zero ndm_state as "no filter" this is a plain subset of what was already
    // returned, so nothing is lost; on the older neigh_dump_filter path a zero mask matched
    // nothing and the dump came back empty, which is the difference that let some devices see
    // an empty table here while `ip neigh` still listed the host. INCOMPLETE and FAILED stay
    // out of the mask and are also dropped again in collect().
    request.message.ndm_state = NUD_PERMANENT | NUD_NOARP | NUD_REACHABLE |
                                NUD_STALE | NUD_DELAY | NUD_PROBE;

    struct sockaddr_nl peer;
    memset(&peer, 0, sizeof(peer));
    peer.nl_family = AF_NETLINK;

    struct timeval timeout;
    timeout.tv_sec = DUMP_TIMEOUT_MS / 1000;
    timeout.tv_usec = (DUMP_TIMEOUT_MS % 1000) * 1000;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    char *buffer = (char *) malloc(RECV_BUF_SIZE);
    if (buffer == NULL) {
        nl_log(ANDROID_LOG_ERROR, LOG_TAG, "Out of memory for the dump buffer");
        close(fd);
        return -1;
    }

    ssize_t sent;
    do {
        sent = sendto(fd, &request, sizeof(request), 0,
                      (struct sockaddr *) &peer, sizeof(peer));
    } while (sent < 0 && errno == EINTR);
    if (sent < 0) {
        nl_log(ANDROID_LOG_ERROR, LOG_TAG, "Cannot ask for the table: %s",
                            strerror(errno));
        free(buffer);
        close(fd);
        return -1;
    }

    int finished = 0;
    int rejected = 0;
    while (!finished) {
        ssize_t received = recv(fd, buffer, RECV_BUF_SIZE, 0);
        if (received < 0) {
            if (errno == EINTR) {
                continue;
            }
            // EAGAIN is the deadline firing. The entries already collected came from real
            // messages, so they are kept rather than thrown away with the rest of the dump.
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                nl_log(ANDROID_LOG_ERROR, LOG_TAG, "recv failed: %s",
                                    strerror(errno));
                rejected = 1;
            }
            break;
        }
        if (received == 0) {
            break;
        }

        struct nlmsghdr *header = (struct nlmsghdr *) buffer;
        int remaining = (int) received;
        for (; NLMSG_OK(header, remaining); header = NLMSG_NEXT(header, remaining)) {
            if (header->nlmsg_type == NLMSG_DONE) {
                finished = 1;
                break;
            }
            if (header->nlmsg_type == NLMSG_ERROR) {
                const struct nlmsgerr *error = (const struct nlmsgerr *) NLMSG_DATA(header);
                nl_log(ANDROID_LOG_ERROR, LOG_TAG,
                                    "The kernel refused the dump: %s",
                                    strerror(error->error < 0 ? -error->error : error->error));
                finished = 1;
                rejected = 1;
                break;
            }
            if (header->nlmsg_type == RTM_NEWNEIGH) {
                collect(header, list, &count);
            }
        }
    }

    free(buffer);
    close(fd);

    if (rejected && count == 0) {
        return -1;
    }
    nl_log(ANDROID_LOG_INFO, LOG_TAG, "Netlink dump gave %d entries", count);
    return count;
}

/**
 * The whole table, one "address hardware-address" line per entry, empty when the kernel
 * could not be asked. Blocking; the caller is expected to already be off the main thread.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_limelight_util_NeighborNetlink_dumpNeighbors(JNIEnv *env, jobject thiz) {
    (void) thiz;

    Neighbor *list = (Neighbor *) calloc(MAX_NEIGHBORS, sizeof(Neighbor));
    if (list == NULL) {
        nl_log(ANDROID_LOG_ERROR, LOG_TAG, "Out of memory for the neighbour list");
        return NULL;
    }

    int count = dump(list);
    if (count < 0) {
        count = 0;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        free(list);
        return NULL;                                        // pending exception
    }
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    if (result == NULL) {
        free(list);
        return NULL;                                        // pending exception
    }

    for (int i = 0; i < count; i++) {
        char line[INET_ADDRSTRLEN + 1 + 18];
        snprintf(line, sizeof(line), "%s %s", list[i].ip, list[i].mac);
        jstring text = (*env)->NewStringUTF(env, line);
        if (text == NULL) {
            free(list);
            return NULL;                                    // pending exception
        }
        (*env)->SetObjectArrayElement(env, result, i, text);
        (*env)->DeleteLocalRef(env, text);
    }

    free(list);
    return result;
}

/**
 * Park the switch. Called from Java before and after every state change of the Tools
 * dropdown; see the flag above.
 */
JNIEXPORT void JNICALL
Java_com_limelight_util_NeighborNetlink_setLogEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    (void) env;
    (void) thiz;
    gLogEnabled = enabled ? 1 : 0;
}
