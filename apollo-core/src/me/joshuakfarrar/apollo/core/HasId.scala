package me.joshuakfarrar.apollo.core

trait HasId[U, I]:
  def id(user: U): I
