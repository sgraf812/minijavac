package minijava.ir.assembler.block;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CodeSegment extends Segment {

  private List<CodeBlock> blocks;
  private Map<Integer, CodeBlock> blocksForNr;
  private List<String> comments;

  public CodeSegment(List<CodeBlock> blocks, List<String> comments) {
    this.blocks = blocks;
    this.comments = comments;
    blocksForNr = new HashMap<>();
    for (CodeBlock block : blocks) {
      blocksForNr.put(block.firmBlock.getNr(), block);
    }
  }

  @Override
  public String toGNUAssembler() {
    StringBuilder builder = new StringBuilder();
    if (comments.size() > 0) {
      builder
          .append(System.lineSeparator())
          .append("/* ")
          .append(String.join(System.lineSeparator(), comments))
          .append("*/\n");
    }
    builder.append("\t.text").append(System.lineSeparator());
    builder.append(
        blocks
            .stream()
            .map(CodeBlock::toGNUAssembler)
            .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator())));
    return builder.toString();
  }

  public void addBlock(CodeBlock block) {
    blocks.add(block);
    blocksForNr.put(block.firmBlock.getNr(), block);
  }

  public void addComment(String comment) {
    comments.add(comment);
  }

  public List<CodeBlock> getBlocks() {
    return Collections.unmodifiableList(blocks);
  }

  public List<String> getComments() {
    return comments;
  }
}
