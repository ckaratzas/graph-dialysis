// Minimal single-process MPI stub -- NOT part of upstream ScaWL. This project always runs
// scawl_seeded.exe with exactly one process (worldSize == 1), so every genuinely distributed
// code path in scawl_seeded.cpp is dead at runtime (confirmed by reading the source: all
// multi-rank communication is behind `if (worldSize > 1)` guards, except MPI_Barrier and the
// MPI_Allgather inside FindSendRecvRole, both implemented here with real, correct single-process
// semantics rather than pure no-ops, since those two are NOT provably dead code).
#ifndef DIALYSIS_MPI_STUB_H
#define DIALYSIS_MPI_STUB_H

#include <cstring>
#include <cstdio>

typedef int MPI_Comm;
typedef int MPI_Datatype;
typedef struct { int MPI_SOURCE; int MPI_TAG; int MPI_ERROR; } MPI_Status;
typedef void *MPI_Request;
typedef long long MPI_Count;

#define MPI_COMM_WORLD 0
#define MPI_BYTE 1
#define MPI_INT 2
#define MPI_LONG 3
#define MPI_ANY_SOURCE (-1)
#define MPI_THREAD_MULTIPLE 3
#define MPI_SUCCESS 0
#define MPI_MAX_PROCESSOR_NAME 256

inline size_t dialysis_mpi_type_size(MPI_Datatype dt)
{
    switch (dt)
    {
        case MPI_BYTE: return 1;
        case MPI_INT: return sizeof(int);
        case MPI_LONG: return sizeof(long);
        default: return 1;
    }
}

inline int MPI_Init_thread(int *argc, char ***argv, int required, int *provided) { *provided = required; return MPI_SUCCESS; }
inline int MPI_Comm_size(MPI_Comm comm, int *size) { *size = 1; return MPI_SUCCESS; }
inline int MPI_Comm_rank(MPI_Comm comm, int *rank) { *rank = 0; return MPI_SUCCESS; }
inline int MPI_Get_processor_name(char *name, int *len) { const char *n = "localhost"; std::strcpy(name, n); *len = (int)std::strlen(n); return MPI_SUCCESS; }
inline int MPI_Finalize() { return MPI_SUCCESS; }
inline int MPI_Barrier(MPI_Comm comm) { return MPI_SUCCESS; }
inline int MPI_Bcast(void *buf, int count, MPI_Datatype dt, int root, MPI_Comm comm) { return MPI_SUCCESS; } // single rank IS root, own buffer already correct
inline int MPI_Allgather(const void *sendbuf, int sendcount, MPI_Datatype sendtype, void *recvbuf, int recvcount, MPI_Datatype recvtype, MPI_Comm comm)
{
    std::memcpy(recvbuf, sendbuf, sendcount * dialysis_mpi_type_size(sendtype));
    return MPI_SUCCESS;
}
inline int MPI_Alltoallv(const void *sendbuf, const int *sendcounts, const int *sdispls, MPI_Datatype sendtype,
                          void *recvbuf, const int *recvcounts, const int *rdispls, MPI_Datatype recvtype, MPI_Comm comm)
{
    // Only reached inside `if (worldSize > 1)` in scawl_seeded.cpp -- never actually called at
    // worldSize==1, but implemented correctly (rank 0's own single chunk) rather than a no-op in
    // case that assumption is ever wrong.
    std::memcpy((char *)recvbuf + rdispls[0] * dialysis_mpi_type_size(recvtype),
                (const char *)sendbuf + sdispls[0] * dialysis_mpi_type_size(sendtype),
                sendcounts[0] * dialysis_mpi_type_size(sendtype));
    return MPI_SUCCESS;
}
inline int MPI_Send(const void *buf, int count, MPI_Datatype dt, int dest, int tag, MPI_Comm comm) { return MPI_SUCCESS; } // dead at worldSize==1
inline int MPI_Recv(void *buf, int count, MPI_Datatype dt, int source, int tag, MPI_Comm comm, MPI_Status *status) { return MPI_SUCCESS; }
inline int MPI_Probe(int source, int tag, MPI_Comm comm, MPI_Status *status) { status->MPI_SOURCE = 0; status->MPI_TAG = tag; return MPI_SUCCESS; }
inline int MPI_Get_count(const MPI_Status *status, MPI_Datatype dt, int *count) { *count = 0; return MPI_SUCCESS; }
inline int MPI_Get_elements_x(const MPI_Status *status, MPI_Datatype dt, MPI_Count *count) { *count = 0; return MPI_SUCCESS; }

#endif
