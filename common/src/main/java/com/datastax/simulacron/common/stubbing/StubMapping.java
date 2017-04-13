package com.datastax.simulacron.common.stubbing;

import com.datastax.oss.protocol.internal.Frame;
import com.datastax.simulacron.common.cluster.Node;

import java.util.List;

/**
 * Defines a mapping for which given a {@link Node} and a received {@link Frame} to that node if
 * these two objects meet some criteria, {@link Action}s are produced which should be performed.
 */
public interface StubMapping {

  /**
   * Determines whether or not the input {@link Node} and {@link Frame} meet the criteria.
   *
   * @param node node receiving the frame.
   * @param frame the sent frame.
   * @return whether or not the matching criteria was met.
   */
  boolean matches(Node node, Frame frame);

  /**
   * Return the {@link Action}s to perform for the given {@link Frame} received by the {@link Node}
   *
   * @param node node receiving the frame.
   * @param frame the sent frame.
   * @return the actions to perform as result of receiving the input frame on the input node.
   */
  List<Action> getActions(Node node, Frame frame);
}