# 参考文献（全文统一）

著录格式：**GB/T 7714-2025 顺序编码制**；按正文首次出现顺序连续编号。
说明：正文引用使用上标数字（如 <sup>[1]</sup>）；技术规范单列于文末，不占学术文献编号。
核对状态：页码带"（待核）"者定稿前须逐条核对出版社页面，不得以估计值替代。

## 一、学术文献（20 篇）

[1] O'NEIL P E, CHENG E, GAWLICK D, et al. The log-structured merge-tree (LSM-tree)[J].
    Acta Informatica, 1996, 33(4): 351-385. DOI: 10.1007/s002360050048.

[2] CHANG F, DEAN J, GHEMAWAT S, et al. Bigtable: a distributed storage system for structured data[C]//
    Proceedings of the 7th USENIX Symposium on Operating Systems Design and Implementation (OSDI).
    Berkeley: USENIX Association, 2006: 205-218.

[3] QIU J, YUAN F, GAO M, et al. HotRAP: hot record retention and promotion for LSM-trees with tiered storage[C]//
    Proceedings of the 2025 USENIX Annual Technical Conference (ATC). Berkeley: USENIX Association, 2025: 497-511.

[4] XU P, et al. RocksMash: building a fast and efficient LSM-tree store by integrating local storage with
    cloud storage[J]. ACM Transactions on Architecture and Code Optimization, 2022, 19(3). DOI: 10.1145/3527452.

[5] DONG H, ZHANG C, LI G, et al. Cloud-native databases: a survey[J]. IEEE Transactions on Knowledge and
    Data Engineering, 2024, 36(12): 7772-7791. DOI: 10.1109/TKDE.2024.3397508.

[6] ZHANG Q, et al. Rethinking data management systems for disaggregated data centers[C]//
    Proceedings of the 10th Biennial Conference on Innovative Data Systems Research (CIDR). 2020.

[7] DAYAN N, ATHANASSOULIS M, IDREOS S. Monkey: optimal navigable key-value store[C]//
    Proceedings of the 2017 ACM International Conference on Management of Data (SIGMOD). 2017.（页码待核）

[8] DAYAN N, IDREOS S. Dostoevsky: better space-time trade-offs for LSM-tree based key-value stores via
    adaptive removal of superfluous merging[C]//Proceedings of the 2018 ACM SIGMOD. 2018.
    DOI: 10.1145/3183713.3196927.

[9] 吕萌, 华文镝, 谢平. 基于 LSM 树的键值存储系统技术研究综述[J]. 计算机科学, 2023, 50(8): 1-15.
    DOI: 10.11896/jsjkx.220900178.

[10] DECANDIA G, HASTORUN D, JAMPANI M, et al. Dynamo: Amazon's highly available key-value store[C]//
     Proceedings of the 21st ACM Symposium on Operating Systems Principles (SOSP). 2007: 205-220.

[11] ONGARO D, OUSTERHOUT J. In search of an understandable consensus algorithm[C]//
     Proceedings of the 2014 USENIX Annual Technical Conference (ATC). 2014: 305-319.

[12] CORBETT J C, DEAN J, EPSTEIN M, et al. Spanner: Google's globally-distributed database[C]//
     Proceedings of the 10th USENIX Symposium on Operating Systems Design and Implementation (OSDI). 2012.（页码待核）

[13] PENG D, DABEK F. Large-scale incremental processing using distributed transactions and notifications[C]//
     Proceedings of the 9th USENIX Symposium on Operating Systems Design and Implementation (OSDI). 2010.（页码待核）

[14] KULKARNI S S, DEMIRBAS M, MADAPPA D, et al. Logical physical clocks[C]//
     Proceedings of the 18th International Conference on Principles of Distributed Systems (OPODIS),
     LNCS 8878. Cham: Springer, 2014: 17-32.

[15] LI B, et al. Enjoy your observability: an industrial survey of microservice tracing and analysis[J].
     Empirical Software Engineering, 2022, 27(1): 25. DOI: 10.1007/s10664-021-10063-9.

[16] BLOOM B H. Space/time trade-offs in hash coding with allowable errors[J].
     Communications of the ACM, 1970, 13(7): 422-426.

[17] MEGIDDO N, MODHA D S. ARC: a self-tuning, low overhead replacement cache[C]//
     Proceedings of the 2nd USENIX Conference on File and Storage Technologies (FAST). 2003.

[18] KLEPPMANN M. Designing data-intensive applications[M]. Sebastopol: O'Reilly Media, 2017.

[19] BERNSTEIN P A, HADZILACOS V, GOODMAN N. Concurrency control and recovery in database systems[M].
     Reading: Addison-Wesley, 1987.

[20] GRAY J, REUTER A. Transaction processing: concepts and techniques[M]. San Francisco: Morgan Kaufmann, 1992.

## 二、技术规范（单列，不计入学术文献）

[S1] Redis. Redis protocol specification (RESP)[EB/OL]. https://redis.io/docs/latest/develop/reference/protocol-spec/.

[S2] Netty Project. Netty documentation[EB/OL]. https://netty.io/.

> 说明：文献 [16]–[20] 为第 2–5 章实现部分引用的经典文献（布隆过滤器、缓存策略、2PC 与事务处理、
> 存储系统综述），与第一章的 15 篇核心文献共同构成本文参考文献；第一章"核心 15 篇"的统计不含此 5 篇。
