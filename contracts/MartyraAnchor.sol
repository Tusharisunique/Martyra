// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

contract MartyraAnchor {
    address public immutable owner;
    mapping(bytes32 => uint64) public anchoredAt;
    event Anchored(bytes32 indexed root, uint32 count, uint64 at);

    constructor() { owner = msg.sender; }

    function anchor(bytes32 root, uint32 count) external {
        require(msg.sender == owner, "not owner");
        require(anchoredAt[root] == 0, "already anchored");
        anchoredAt[root] = uint64(block.timestamp);
        emit Anchored(root, count, uint64(block.timestamp));
    }
}
